/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.azure.eventhub;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.Collections2;
import com.microsoft.azure.eventhubs.ConnectionStringBuilder;
import com.microsoft.azure.eventhubs.EventData;
import com.microsoft.azure.eventhubs.EventHubClient;
import com.microsoft.azure.eventhubs.EventHubException;
import com.microsoft.azure.eventhubs.IllegalConnectionStringFormatException;
import com.microsoft.azure.eventhubs.impl.EventHubClientImpl;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.behavior.SystemResource;
import org.apache.nifi.annotation.behavior.SystemResourceConsideration;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.processors.azure.storage.utils.FlowFileResultCarrier;
import org.apache.nifi.stream.io.StreamUtils;
import org.apache.nifi.util.StopWatch;

@SupportsBatching
@Tags({"microsoft", "azure", "cloud", "eventhub", "events", "streams", "streaming"})
@InputRequirement(Requirement.INPUT_REQUIRED)
@CapabilityDescription("Sends the contents of a FlowFile to a Windows Azure Event Hub. Note: the content of the FlowFile will be buffered into memory before being sent, "
        + "so care should be taken to avoid sending FlowFiles to this Processor that exceed the amount of Java Heap Space available. "
        + "Also please be aware that this processor creates a thread pool of 4 threads for Event Hub Client. They will be extra threads other than the concurrent tasks scheduled for this processor.")
@SystemResourceConsideration(resource = SystemResource.MEMORY)
public class PutAzureEventHub extends AbstractProcessor {
    static final PropertyDescriptor EVENT_HUB_NAME = new PropertyDescriptor.Builder()
            .name("Event Hub Name")
            .description("The name of the Azure Event Hub to send to")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(true)
            .build();
    static final PropertyDescriptor NAMESPACE = new PropertyDescriptor.Builder()
            .name("Event Hub Namespace")
            .description("The Azure Namespace that the Event Hub is assigned to. This is generally equal to <Event Hub Name>-ns")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.NONE)
            .required(true)
            .build();
    static final PropertyDescriptor ACCESS_POLICY = new PropertyDescriptor.Builder()
            .name("Shared Access Policy Name")
            .description("The name of the Event Hub Shared Access Policy. This Policy must have Send permissions.")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.NONE)
            .required(true)
            .build();
    static final PropertyDescriptor POLICY_PRIMARY_KEY = new PropertyDescriptor.Builder()
            .name("Shared Access Policy Primary Key")
            .description("The primary key of the Event Hub Shared Access Policy")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.NONE)
            .sensitive(true)
            .required(true)
            .build();
    static final PropertyDescriptor PARTITIONING_KEY_ATTRIBUTE_NAME = new PropertyDescriptor.Builder()
            .name("partitioning-key-attribute-name")
            .displayName("Partitioning Key Attribute Name")
            .description("If specified, the value from argument named by this field will be used as a partitioning key to be used by event hub.")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.NONE)
            .addValidator(StandardValidators.ATTRIBUTE_KEY_VALIDATOR)
            .defaultValue(null)
            .build();
    static final PropertyDescriptor MAX_BATCH_SIZE = new PropertyDescriptor.Builder()
            .name("max-batch-size")
            .displayName("Maximum batch size")
            .description("Maximum count of flow files being processed in one batch.")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.NONE)
            .addValidator(StandardValidators.NUMBER_VALIDATOR)
            .defaultValue("100")
            .build();

    static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("Any FlowFile that is successfully sent to the Azure Event Hub will be transferred to this Relationship.")
            .build();
    static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("Any FlowFile that could not be sent to the Azure Event Hub will be transferred to this Relationship.")
            .build();

    private volatile BlockingQueue<EventHubClient> senderQueue = new LinkedBlockingQueue<>();

    private final static List<PropertyDescriptor> propertyDescriptors;
    private final static Set<Relationship> relationships;

    /*
    * Will ensure that the list of property descriptors is build only once.
    * Will also create a Set of relationships
    */
    static {
        List<PropertyDescriptor> _propertyDescriptors = new ArrayList<>();
        _propertyDescriptors.add(EVENT_HUB_NAME);
        _propertyDescriptors.add(NAMESPACE);
        _propertyDescriptors.add(ACCESS_POLICY);
        _propertyDescriptors.add(POLICY_PRIMARY_KEY);
        _propertyDescriptors.add(PARTITIONING_KEY_ATTRIBUTE_NAME);
        _propertyDescriptors.add(MAX_BATCH_SIZE);
        propertyDescriptors = Collections.unmodifiableList(_propertyDescriptors);

        Set<Relationship> _relationships = new HashSet<>();
        _relationships.add(REL_SUCCESS);
        _relationships.add(REL_FAILURE);
        relationships = Collections.unmodifiableSet(_relationships);
    }
    @Override
    public Set<Relationship> getRelationships() {
        return relationships;
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return propertyDescriptors;
    }

    private ScheduledExecutorService executor;

    @OnScheduled
    public final void setupClient(final ProcessContext context) throws ProcessException{
    }

    @OnStopped
    public void tearDown() {
        EventHubClient sender;
        while ((sender = senderQueue.poll()) != null) {
            sender.close();
        }
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
    	populateSenderQueue(context);
        
        final StopWatch stopWatch = new StopWatch(true);
    	
        // Get N flow files
        final int maxBatchSize = NumberUtils.toInt(context.getProperty(MAX_BATCH_SIZE).getValue(), 100);
        final List<FlowFile> flowFileList = session.get(maxBatchSize);
        
        // Convert and send each flow file
        final BlockingQueue<CompletableFuture<FlowFileResultCarrier<Relationship>>> futureQueue = new LinkedBlockingQueue<>();
        for (FlowFile flowFile : flowFileList) {
            if (flowFile == null) {
                continue;
            }
            
            futureQueue.offer(handleFlowFile(flowFile, context, session));
		}
        
        //Wait for all futures - A barrier
        try {   	
	        for (CompletableFuture<FlowFileResultCarrier<Relationship>> completableFuture : futureQueue) {
	        	completableFuture.join();
	        	
	        	final FlowFileResultCarrier<Relationship> flowFileResult = completableFuture.get();
	        	final FlowFile flowFile = flowFileResult.getFlowFile();
	        	
	        	if(flowFileResult.getResult() == REL_SUCCESS) {
	    	        final String namespace = context.getProperty(NAMESPACE).getValue();
	    	        final String eventHubName = context.getProperty(EVENT_HUB_NAME).getValue();
	    	        session.getProvenanceReporter().send(flowFile, "amqps://" + namespace + ".servicebus.windows.net" + "/" + eventHubName, stopWatch.getElapsed(TimeUnit.MILLISECONDS));
	    	        session.transfer(flowFile, REL_SUCCESS);
	    	        
	        	} else {
	        		final Throwable processException = flowFileResult.getException();
	    			getLogger().error("Failed to send {} to EventHub due to {}; routing to failure", new Object[]{flowFile, processException}, processException);
	                session.transfer(session.penalize(flowFile), REL_FAILURE);
	        	}
	        	
			}
        
		} catch (InterruptedException | ExecutionException | CancellationException | CompletionException e) {
			getLogger().error("Batch processing failed", e);
			session.rollback();
			throw new ProcessException("Batch processing failed", e);
		}
    }
    
    /**
     * @param flowFile to be converted to a message and sent to Eventhub (Body = content, User Properties = attributes, partitioning key = value configured attribute)
     * @param context of this processor task
     * @param session under which is this flow file being managed
     * 
     * @return Completable future carrying the context of flowfile used as a base for message being send. Never Null.
     * */
    private CompletableFuture<FlowFileResultCarrier<Relationship>> handleFlowFile(FlowFile flowFile, final ProcessContext context, final ProcessSession session) {

    	// Read message body
        final byte[] buffer = new byte[(int) flowFile.getSize()];
        session.read(flowFile, in -> StreamUtils.fillBuffer(in, buffer));
        
        // Lift partitioning key
        final String partitioningKey;
        final String partitioningKeyAttributeName = context.getProperty(PARTITIONING_KEY_ATTRIBUTE_NAME).getValue();
        if (StringUtils.isNotBlank(partitioningKeyAttributeName)) {
            partitioningKey = flowFile.getAttribute(partitioningKeyAttributeName);
        } else {
        	partitioningKey = null;
        }

        // Prepare user properties
        final Map<String, Object> userProperties;
        Map<String, String> attributes = flowFile.getAttributes();
        if(attributes == null) {
        	userProperties = Collections.emptyMap();
        }else {
        	userProperties = new HashMap<>(attributes);
        }
        
        // Send the message
        try {
        	return sendMessage(buffer, partitioningKey, userProperties)
            		.thenApplyAsync(param -> {
            			return new FlowFileResultCarrier<Relationship>(flowFile, REL_SUCCESS);
            			})
            		.exceptionally(processException -> {
            			return new FlowFileResultCarrier<Relationship>(flowFile, REL_FAILURE, processException);
            			});

        } catch (final ProcessException processException) {
            return CompletableFuture.completedFuture(new FlowFileResultCarrier<Relationship>(flowFile, REL_FAILURE, processException));
        }
    }
    
    private void populateSenderQueue(ProcessContext context) {
        if(senderQueue.size() == 0){
            final int numThreads = context.getMaxConcurrentTasks();
            senderQueue = new LinkedBlockingQueue<>(numThreads);
            executor = Executors.newScheduledThreadPool(4);
            final String policyName = context.getProperty(ACCESS_POLICY).getValue();
            final String policyKey = context.getProperty(POLICY_PRIMARY_KEY).getValue();
            final String namespace = context.getProperty(NAMESPACE).getValue();
            final String eventHubName = context.getProperty(EVENT_HUB_NAME).getValue();
            for (int i = 0; i < numThreads; i++) {
                final EventHubClient client = createEventHubClient(namespace, eventHubName, policyName, policyKey, executor);
                if(null != client) {
                    senderQueue.offer(client);
                }
            }
        }
    }
    
    protected EventHubClient createEventHubClient(
        final String namespace,
        final String eventHubName,
        final String policyName,
        final String policyKey,
        final ScheduledExecutorService executor)
        throws ProcessException{

        try {
            EventHubClientImpl.USER_AGENT = "ApacheNiFi-azureeventhub/2.3.2";
            return EventHubClient.createSync(getConnectionString(namespace, eventHubName, policyName, policyKey), executor);
        } catch (IOException | EventHubException | IllegalConnectionStringFormatException e) {
            getLogger().error("Failed to create EventHubClient due to {}", e);
            throw new ProcessException(e);
        }
    }
    
    protected String getConnectionString(final String namespace, final String eventHubName, final String policyName, final String policyKey){
        return new ConnectionStringBuilder().setNamespaceName(namespace).setEventHubName(eventHubName).setSasKeyName(policyName).setSasKey(policyKey).toString();
    }
    
    protected CompletableFuture<Void> sendMessage(final byte[] buffer, String partitioningKey, Map<String, ? extends Object> userProperties) throws ProcessException {

        final EventHubClient sender = senderQueue.poll();
        if(sender == null) {
        	throw new ProcessException("No EventHubClients are configured for sending");
        }
        
        EventData eventData = EventData.create(buffer);
        Map<String, Object> properties = eventData.getProperties();
        if(userProperties != null && properties != null) {
        	properties.putAll(userProperties);
        }
        
    	final CompletableFuture<Void> eventFuture;
    	if(StringUtils.isNotBlank(partitioningKey)) {
    		eventFuture = sender.send(eventData, partitioningKey);
    	}else {
    		eventFuture = sender.send(eventData);
    	}

    	senderQueue.offer(sender);
    	
    	return eventFuture;
    }
}
