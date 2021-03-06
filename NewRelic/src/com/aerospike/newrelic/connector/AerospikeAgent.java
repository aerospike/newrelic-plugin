package com.aerospike.newrelic.connector;

import static com.aerospike.newrelic.utils.Constants.CLUSTER_FALLBACK_NAME;
import static com.aerospike.newrelic.utils.Constants.DEFAULT_PLUGIN_NAME;
import static com.aerospike.newrelic.utils.Constants.LATENCY;
import static com.aerospike.newrelic.utils.Constants.LATENCY_BUCKETS;
//import static com.aerospike.newrelic.utils.Constants.LATENCY_CATEGORY;
import static com.aerospike.newrelic.utils.Constants.LATENCY_STATS;
import static com.aerospike.newrelic.utils.Constants.METRIC_BASE_NAME;
import static com.aerospike.newrelic.utils.Constants.NAMESPACE_STATS;
import static com.aerospike.newrelic.utils.Constants.NODE_STATS;
import static com.aerospike.newrelic.utils.Constants.READS;
import static com.aerospike.newrelic.utils.Constants.SLASH;
import static com.aerospike.newrelic.utils.Constants.SUMMARY;
import static com.aerospike.newrelic.utils.Constants.THROUGHPUT_STATS;
import static com.aerospike.newrelic.utils.Constants.WRITES;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import com.aerospike.client.Host;
import com.aerospike.client.Log;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.AerospikeException.Connection;
import com.aerospike.client.cluster.Node;
import com.aerospike.newrelic.utils.Utils;
import com.newrelic.metrics.publish.Agent;
import com.newrelic.metrics.publish.configuration.ConfigurationException;
import com.newrelic.metrics.publish.util.Logger;
//import com.sun.xml.internal.bind.v2.schemagen.xmlschema.List;

/**
 * Agent for Aerospike. This agent will log Aerospike statistics, namespace
 * statistics, latency and throughput metrics for an Aerospike node.
 *
 */
public class AerospikeAgent extends Agent {
    
    private static final String GUID = "com.aerospike.newrelic.connector";
    private static final String VERSION = "2.0.1";
    
    private String user;
    private String password;
    private ArrayList<Host> host_list;
    private String clusterName;
    private Base base;
    private String metricBaseName;
    
    Map<String, Float> totalReadTps;
    Map<String, Float> totalWriteTps;
    Map<String, Map<String, Float>> clusterWideLatency;
    Map<String, Map<String, Float>> clusterWideNamespaceLatency;
    
    private static final Logger logger = Logger.getLogger(AerospikeAgent.class);
    
    
    /**
     * Constructor for Aerospike Agent
     *
     * @param Aerospike
     *            node details (host, port, user, password, node name)
     * @throws ConfigurationException
     *             if error reading configuration parameters.
     */
    public AerospikeAgent(ArrayList<String> seed_list, String user, String password, String clusterName)
    throws ConfigurationException {
        super(GUID, VERSION);
        try {
            this.user = user;
            this.password = password;
            this.clusterName = clusterName;
            this.metricBaseName = METRIC_BASE_NAME;
            
            /* creating host list from seed list */
            this.host_list = new ArrayList<Host>();
            for (String seed : seed_list) {
                String[] host_port = seed.split(":");
                Host host = new Host(host_port[0], Integer.parseInt(host_port[1]));
                this.host_list.add(host);
            }
            
            /* creating map for cluster-wide reads and writes */
            totalReadTps = new HashMap<String, Float>();
            totalWriteTps = new HashMap<String, Float>();
            
            /* creating map for cluster-wide latency */
            clusterWideLatency = new HashMap<String, Map<String, Float>>();
            clusterWideNamespaceLatency = new HashMap<String, Map<String, Float>>();
            
            /* Creating AerospikeClient */
            this.base = new Base();
            this.base.createAerospikeClient(this.host_list, this.user, this.password);
            
            /* Set default values to readTpsHistory and writeTpsHistory */
            setDefaultsToTpsHistory();
            
            //logger.info("Aerospike Agent initialized: ", formatAgentParams(host, port, user, password, clusterName));
            logger.info("Aerospike Agent initialized: ", formatAgentParams(seed_list, user, password, clusterName));
            
            
        } catch (Exception exception) {
            logger.error("Error reading configuration parameters : ", exception);
            throw new ConfigurationException("Error reading configuration parameters...", exception);
        }
    }
    
    /**
     * Format Agent parameters for logging
     *
     * @param clusterName
     * @param password
     * @param user
     * @param port
     * @param host
     *
     * @return A formatted String representing the Agent parameters
     */
    
    private String formatAgentParams(ArrayList<String> seed_list, String user, String password, String clusterName) {
        StringBuilder builder = new StringBuilder();
        builder.append("seed_list").append(seed_list.toString()).append(" | ");
        
        builder.append("user: ").append(user == null ? "n/a" : user).append(" | ");
        builder.append("password: ").append(password == null ? "n/a" : password).append(" | ");
        builder.append("clusterName: ").append(clusterName);
        return builder.toString();
    }
    
    /**
     * Method to set default values to readTpsHistory and writeTpsHistory
     *
     */
    public void setDefaultsToTpsHistory() {
        Long timeStamp = System.currentTimeMillis() / 1000l;
        Node[] nodes = base.getAerospikeNodes();
        
        for (Node node : nodes) {
            Map<String, String> readTpsHistory = new HashMap<String, String>();
            readTpsHistory.put("timeStamp", Long.toString(timeStamp));
            readTpsHistory.put("totalTps", null);
            readTpsHistory.put("successTps", null);
            
            Main.readTpsHistory.put(node.getHost().name, readTpsHistory);
            
            Map<String, String> writeTpsHistory = new HashMap<String, String>();
            writeTpsHistory.put("timeStamp", Long.toString(timeStamp));
            writeTpsHistory.put("totalTps", null);
            writeTpsHistory.put("successTps", null);
            
            Main.readTpsHistory.put(node.getHost().name, writeTpsHistory);
        }
    }
    
    /**
     * Method to return agent name.
     *
     * @return String Aerospike Agent Name
     */
    @Override
    public String getAgentName() {
        return clusterName.equals(CLUSTER_FALLBACK_NAME) ? DEFAULT_PLUGIN_NAME : clusterName;
    }
    
    /**
     * Method to fetch node statistics from base and report to new relic.
     * For ASD>3.9 derive used_bytes_memory and use_bytes_disk.
     * @return Map<String, String> A map of node statistics
     */
    public Map<String, String> reportNodeStatistics(Node node) {
        logger.debug("Reporting node stats: " + node);

        
        String nodeStatPrefix = metricBaseName + SLASH + NODE_STATS + SLASH + node.getHost().name + SLASH;
        Map<String, String> nodeStats = base.getNodeStatistics(node);

		boolean newAsd = base.newAsdversion(node);
        if (newAsd == true) {
        	logger.debug("New ASD, collect used_disk and used_memory.");
            Float totalUsedMemory = (float) 0;
            Float totalUsedDisk = (float) 0;
            
            Map<String, String> memoryStats = base.getMemoryStats(node);
            Map<String, String> diskStats = base.getDiskStats(node);
            
            if (memoryStats.get("used_bytes_memory") != null && memoryStats.get("used_bytes_memory") != "n/s")
            	totalUsedMemory = Float.parseFloat(memoryStats.get("used_bytes_memory"));
            
            if (diskStats.get("used_bytes_disk") != null && diskStats.get("used_bytes_disk") != "n/s")
            	totalUsedDisk = Float.parseFloat(diskStats.get("used_bytes_disk"));
            
            reportMetric(nodeStatPrefix + "used_bytes_memory", "", totalUsedMemory);
            logger.debug("Reprting metics, metric name: " + nodeStatPrefix + "used_bytes_memory" + ", value: " + totalUsedMemory);
                        
            reportMetric(nodeStatPrefix + "used_bytes_disk", "", totalUsedDisk);
            logger.debug("Reprting metics, metric name: " + nodeStatPrefix + "used_bytes_disk" + ", value: " + totalUsedDisk);
            	
        	
        }

        for (Map.Entry<String, String> nodeStat : nodeStats.entrySet()) {
            String metric_name = nodeStatPrefix + nodeStat.getKey();
            float value = Float.parseFloat(nodeStat.getValue());
            reportMetric(metric_name, "", value);
            logger.debug("Reprting metics, metric name: " + metric_name + ", value: " + value);
        }
        return nodeStats;
    }
    
    
    /**
     * Method to set default values in totalReadTps and totalWriteTps maps
     *
     */
    public void initTps() {
        totalReadTps.clear();
        totalReadTps.put("successTps", (float) 0);
        totalReadTps.put("totalTps", (float) 0);
        
        totalWriteTps.clear();
        totalWriteTps.put("successTps", (float) 0);
        totalWriteTps.put("totalTps", (float) 0);
    }
    
    /**
     * Method to report node throughput.
     *
     */
    public void reportThroughput(Node node) {
        logger.debug("Report node throughput.");
        Map<String, Map<String, String>> tps = base.getThroughput(node);
        Map<String, String> readTps = tps.get("reads");
        Map<String, String> writeTps = tps.get("writes");
        
        String baseThroughputMetric = metricBaseName + SLASH + THROUGHPUT_STATS + SLASH;
        if (readTps != null) {
            String read_metric_prefix = baseThroughputMetric + node.getHost().name + SLASH + READS + SLASH;
            if (readTps.get("successTps") == null) {
                totalReadTps.put("successTps", (float) 0);
                reportMetric(read_metric_prefix + "success", "", 0);
                logger.debug("Reprting metics, metric name: " + read_metric_prefix + "success" + ", value: " + 0);
                
            } else {
                totalReadTps.put("successTps", totalReadTps.get("successTps") + Integer.valueOf(readTps.get("successTps")));
                reportMetric(read_metric_prefix + "success", "", Integer.valueOf(readTps.get("successTps")));
                logger.debug("Reprting metics, metric name: " + read_metric_prefix + "success" + ", value: " + Integer.valueOf(readTps.get("successTps")));
                
            }
            
            if (readTps.get("totalTps") == null) {
                totalReadTps.put("totalTps", (float) 0);
                reportMetric(read_metric_prefix + "total", "", 0);
                logger.debug("Reprting metics, metric name: " + read_metric_prefix + "total" + ", value: " + 0);
                
            } else {
                totalReadTps.put("totalTps", totalReadTps.get("totalTps") + Integer.valueOf(readTps.get("totalTps")));
                reportMetric(read_metric_prefix + "total", "", Integer.valueOf(readTps.get("totalTps")));
                logger.debug("Reprting metics, metric name: " + read_metric_prefix + "total" + ", value: " + Integer.valueOf(readTps.get("totalTps")));
                
            }
        }
        
        if (writeTps != null) {
            String write_metric_name = baseThroughputMetric + node.getHost().name + SLASH + WRITES + SLASH;
            if (writeTps.get("successTps") == null) {
                totalWriteTps.put("successTps", (float) 0);
                reportMetric(write_metric_name + "success", "", 0);
                logger.debug("Reprting metics, metric name: " + write_metric_name + "success" + ", value: " + 0);
                
            } else {
                totalWriteTps.put("successTps", totalWriteTps.get("successTps") + Integer.valueOf(writeTps.get("successTps")));
                reportMetric(write_metric_name + "success", "", Integer.valueOf(writeTps.get("successTps")));
                logger.debug("Reprting metics, metric name: " + write_metric_name + "success" + ", value: " + Integer.valueOf(writeTps.get("successTps")));
                
            }
            if (writeTps.get("totalTps") == null) {
                totalWriteTps.put("totalTps", (float) 0);
                reportMetric(write_metric_name + "total", "", 0);
                logger.debug("Reprting metics, metric name: " + write_metric_name + "total" + ", value: " + 0);
                
            } else {
                totalWriteTps.put("totalTps",totalWriteTps.get("totalTps") + Integer.valueOf(writeTps.get("totalTps")));
                reportMetric(write_metric_name + "total", "", Integer.valueOf(writeTps.get("totalTps")));
                logger.debug("Reprting metics, metric name: " + write_metric_name + "total" + ", value: " + Integer.valueOf(writeTps.get("totalTps")));
                
            }
        }
    }
    
    /**
     * Method to report Summary metric
     *
     */
    public void reportSummaryMetric() {
        logger.debug("Reporting summary metric.");
        String baseSummaryMetric = METRIC_BASE_NAME + SLASH + SUMMARY + SLASH;
        /* Getting one active node from cluster and getting its stats*/
        Float totalUsedMemory = (float) 0;
        Float totalUsedDisk = (float) 0;
        
        Node[] nodes = base.getAerospikeNodes();
        
        for (Node node : nodes) {
            Map<String, String> memoryStats = base.getMemoryStats(node);
            Map<String, String> diskStats = base.getDiskStats(node);
            
            if (memoryStats.get("used_bytes_memory") != null && memoryStats.get("used_bytes_memory") != "n/s")
            	totalUsedMemory += Float.parseFloat(memoryStats.get("used_bytes_memory"));
            if (diskStats.get("used_bytes_disk") != null && diskStats.get("used_bytes_disk") != "n/s")
            	totalUsedDisk += Float.parseFloat(diskStats.get("used_bytes_disk"));
        }
        
        Node node = base.getAerospikeNodes()[0];
        if (node != null) {
            Map<String, String> nodeStats = base.getNodeStatistics(node);
            if (nodeStats != null) {
                reportMetric(baseSummaryMetric + "cluster_size", "", Float.parseFloat(nodeStats.get("cluster_size")));
                logger.debug("Reprting metics, metric name: " + baseSummaryMetric + "cluster_size" + ", value: " + Float.parseFloat(nodeStats.get("cluster_size")));
            }
        }

        reportMetric(baseSummaryMetric + "used_bytes_memory", "", totalUsedMemory);
        logger.debug("Reprting metics, metric name: " + baseSummaryMetric + "used_bytes_memory" + ", value: " + totalUsedMemory);
        
        
        reportMetric(baseSummaryMetric + "used_bytes_disk", "", totalUsedDisk);
        logger.debug("Reprting metics, metric name: " + baseSummaryMetric + "used_bytes_disk" + ", value: " + totalUsedDisk);      
    }
    
    /**
     * Method to report cluster-wide reads and writes under Summary metric
     *
     */
    public void reportTotalTps() {
        logger.debug("Report total tps.");
        String baseSummaryTpsMetric = METRIC_BASE_NAME + SLASH + SUMMARY + SLASH;
        String read_metric = baseSummaryTpsMetric + READS + SLASH;
        String write_metric = baseSummaryTpsMetric + WRITES + SLASH;
        
        reportMetric(read_metric + "success", "", totalReadTps.get("successTps"));
        logger.debug("Reprting metics, metric name: " + read_metric + ", value: " + totalReadTps.get("successTps"));
        
        reportMetric(read_metric + "total", "", totalReadTps.get("totalTps"));
        logger.debug("Reprting metics, metric name: " + read_metric + ", value: " + totalReadTps.get("totalTps"));
        
        reportMetric(write_metric + "success", "", totalWriteTps.get("successTps"));
        logger.debug("Reprting metics, metric name: " + write_metric + ", value: " + totalWriteTps.get("successTps"));
        
        reportMetric(write_metric + "total", "", totalWriteTps.get("totalTps"));
        logger.debug("Reprting metics, metric name: " + write_metric + ", value: " + totalWriteTps.get("totalTps"));
        
    }
    
    /**
     * A method to report node latency for reads, writes_master, proxy, udf and
     * query.
     */
    public void reportNodesLatency() {
        logger.debug("Reporting node latency.");
        String baseLatentyMetric = metricBaseName + SLASH + LATENCY_STATS + SLASH;
        /* setting default values to cluster-wide latency map */
        initClusterWideLatency();
        Node[] nodes = base.getAerospikeNodes();
        for (Node node : nodes) {
            Map<String, Map<String, String>> latency = base.getNodeLatency(node);
            logger.info("Node latency: " + latency);
            for (Map.Entry<String, Map<String, String>> entry : latency.entrySet()) {
                String key = entry.getKey();
                for (Map.Entry<String, String> dataEntry : entry.getValue().entrySet()) {
                    
                    String metric_name_prefix = baseLatentyMetric + node.getHost().name + SLASH + key + "/" + dataEntry.getKey();
                    float metric_value = Float.parseFloat(dataEntry.getValue().split(";")[0]);
                    float metric_pct = Float.parseFloat(dataEntry.getValue().split(";")[1]);
                    
                    reportMetric(metric_name_prefix + "/value", "", metric_value);
                    logger.debug("Reprting metics, metric name: " + metric_name_prefix + "/value" + ", value: " + metric_value);
                    
                    reportMetric(metric_name_prefix + "/pct", "", metric_pct);
                    logger.debug("Reprting metics, metric name: " + metric_name_prefix + "/pct" + ", value: " + metric_pct);
                    
                    /* calculating cluster-wide latency */
                    
                    calculateClusterWideLatency(key, dataEntry.getKey(),
                    		metric_value, node);
                    
                }
            }
        }
        /* reporting cluster-wide latency */
        reportClusterWideLatency(nodes.length);
    }
    
    /**
     * Method to set default values to cluster-wide latency map.
     *
     */
    
    private void initClusterWideLatency() {
    	logger.debug("Init clusterwide latency.");
        clusterWideLatency.clear();
        clusterWideNamespaceLatency.clear();
      
    }
    
    /**
     * Init latency buckets for some category.
     */
    private void initClusterWideLatencyBucket(Map<String, Map<String, Float>> clusterWideLatency, String category) {
        Map<String, Float> bucketMap = new HashMap<String, Float>();
        for (String bucket : LATENCY_BUCKETS) {
            bucketMap.put(bucket, (float) 0);
        }
        clusterWideLatency.put(category, bucketMap);
    }

    
    /**
     * Method to calculate latency
     *
     * @param clusterWideLatency: latency map
     * @param category
     * @param bucket
     * @param bucketValue
     */ 
    private void calculateLatency(Map<String, Map<String, Float>> clusterWideLatency, String category, String bucket, float bucketValue) {
        logger.debug("Calculating latency.");
        float value = (float)0.0;
        if (clusterWideLatency.containsKey(category)) {
            value = clusterWideLatency.get(category).get(bucket) + bucketValue;
        } else {
            initClusterWideLatencyBucket(clusterWideLatency, category);
        }
        clusterWideLatency.get(category).put(bucket, value);
    }

    
    /**
     * Method to calculate cluster-wide latency and cluster wide namespace latency for ASD>3.9
     *
     * @param category
     * @param bucket
     * @param bucketValue
     */
    private void calculateClusterWideLatency(String category, String bucket, float bucketValue, Node node) {
        logger.debug("Calculating clusterwide latency.");
        boolean newAsd = base.newAsdversion(node);

        if (newAsd == true) {
    		String[] lst = category.split("-");
    		//calculateLatency(clusterWideLatency, category, bucket, bucketValue);
    		if(lst.length > 0)
        		calculateLatency(clusterWideNamespaceLatency, category, bucket, bucketValue);
    		category = lst[0];
    		
    	}
		calculateLatency(clusterWideLatency, category, bucket, bucketValue);
    }
    
    /**
     * Method to report latency
     *
     * @param clusterSize
     * @param clusterWideLatency: latency map
     */
    private void reportLatency(int clusterSize, Map<String, Map<String, Float>> clusterWideLatency) {
        logger.debug("Reporting latency.");
        logger.debug(clusterWideLatency);
        String baseClusterWideLatencyMetric = METRIC_BASE_NAME + SLASH + SUMMARY + SLASH + LATENCY + SLASH;
        for (Map.Entry<String, Map<String, Float>> entry : clusterWideLatency.entrySet()) {
            String key = entry.getKey();
            for (Map.Entry<String, Float> dataEntry : entry.getValue().entrySet()) {
                String metric_name = baseClusterWideLatencyMetric + key + SLASH + dataEntry.getKey() + SLASH + "value";
                
                //if (key.equals(LATENCY_CATEGORY[2])) {
                if (key.contains("query")) {
                    
                    /* calculate average for query category */
                    if (clusterSize > 0) {
                        reportMetric(metric_name, "", dataEntry.getValue() / clusterSize);
                        logger.debug("Reprting metics, metric name: " + metric_name + ", value: " + dataEntry.getValue() / clusterSize);
                    }
                    
                } else {
                    reportMetric(metric_name, "", dataEntry.getValue());
                    logger.debug("Reprting metics, metric name: " + metric_name + ", value: " + dataEntry.getValue());
                    
                }
                
            }
        }
    }
    
    
    private void reportClusterWideLatency(int clusterSize) {
        logger.debug("Reporting clusterwide latency.");
    	reportLatency(clusterSize, clusterWideLatency);
    	reportLatency(clusterSize, clusterWideNamespaceLatency);
    }

    
    /**
     * Method to report namespace statistics.
     * 
     * @param namespace
     *            namespace name to fetch the statistics
     */
    public void reportNamespaceStats() {
        String[] namespaces = base.getNamespaces();
        if (namespaces.length != 0) {
            for (String namespace : namespaces) {
                logger.debug("Reporting namespace stats. Namespace: ", namespace);
                String namespaceBaseMatric = metricBaseName + SLASH;
                Node[] nodes = base.getAerospikeNodes();
                for (Node node : nodes) {
                    Map<String, String> namespaceStats = base.getNamespaceStatistics(namespace, node);
                    logger.debug("Namespacestats: " + namespaceStats);
                    if (namespaceStats != null && namespaceStats.size() != 0) {
                        String namespacePrefix = namespaceBaseMatric + NAMESPACE_STATS + SLASH + node.getHost().name + SLASH + namespace + SLASH;
                        for (Map.Entry<String, String> stat : namespaceStats.entrySet()) {
                            String metric_name = namespacePrefix + stat.getKey();
                            float value = Float.parseFloat(stat.getValue());
                            reportMetric(metric_name, "",value);
                            logger.debug("Reprting metics, metric name: " + metric_name + ", value: " + value);
                        }
                    }
                }								
            }
        }
        
    }
    
    
    public Map<String, Map<String, String>> reportNodesData() {
        logger.info("Report data for nodes");
        Map<String, Map<String, String>> perNodeStats = new HashMap<String, Map<String, String>>();
        Node[] nodes = base.getAerospikeNodes();
        
        for (Node node : nodes) {
            Map<String, String> nodeStats = base.getNodeStatistics(node);
            perNodeStats.put(node.getHost().name, nodeStats);
            reportNodeStatistics(node);
            reportThroughput(node);	
        }
        return perNodeStats;
    }
    
    /**
     * A method to submit Aerospike metrics to New Relic, periodically.
     * 
     */
    @Override
    public void pollCycle() {
        try {
            logger.info("********** Reporting stats for cluster: ", this.clusterName + " **********");
            /* set default values for cluster-wide TPS */
            initTps();
            
            Map<String, Map<String, String>> perNodeStats = reportNodesData();
            Main.setStatistcs(perNodeStats);
            
            reportNodesLatency();
            reportNamespaceStats();
            reportSummaryMetric();
            reportTotalTps();
            
            
        } catch (Connection connection) {
            logger.error("Exception : " + connection.getMessage());
            //logger.error("Plugin is should not stop. earlier it was a bug");
            //System.exit(-1);
        } catch (AerospikeException aerospikeException) {
            logger.error("Exception : " + aerospikeException.getMessage());
        } catch (ArrayIndexOutOfBoundsException arrayIndexOutOfBoundsException) {
            logger.info("Server is starting");
        } catch (Exception exception) {
            logger.error("Exception : " + exception);
        }
    }
}
