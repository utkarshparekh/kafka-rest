package io.confluent.kafkarest.integration;

import io.confluent.kafkarest.Main;
import io.confluent.kafkarest.validation.JacksonMessageBodyProvider;
import kafka.server.KafkaConfig;
import kafka.server.KafkaServer;
import kafka.utils.SystemTime$;
import kafka.utils.TestUtils;
import kafka.utils.Utils;
import kafka.utils.ZKStringSerializer$;
import kafka.zk.EmbeddedZookeeper;
import org.I0Itec.zkclient.ZkClient;
import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.Before;
import scala.collection.JavaConversions;

import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import java.util.*;

/**
 * Test harness to run against a real, local Kafka cluster and REST proxy. This is essentially Kafka's
 * ZookeeperTestHarness and KafkaServerTestHarness traits combined and ported to Java with the addition
 * of the REST proxy. Defaults to a 1-ZK, 3-broker, 1 REST proxy cluster.
 */
public abstract class ClusterTestHarness {
    public static final int DEFAULT_NUM_BROKERS = 3;

    // Shared config
    protected Queue<Integer> ports;

    // ZK Config
    protected int zkPort;
    protected String zkConnect;
    protected EmbeddedZookeeper zookeeper;
    protected ZkClient zkClient;
    protected int zkConnectionTimeout = 6000;
    protected int zkSessionTimeout = 6000;

    // Kafka Config
    protected List<KafkaConfig> configs = null;
    protected List<KafkaServer> servers = null;
    protected String brokerList = null;

    protected Properties restProperties = null;
    protected Server restServer = null;
    protected String restConnect = null;

    public ClusterTestHarness() {
        this(DEFAULT_NUM_BROKERS);
    }

    public ClusterTestHarness(int numBrokers) {
        // 1 port per broker + ZK + REST server
        this(numBrokers, numBrokers + 2);
    }

    public ClusterTestHarness(int numBrokers, int numPorts) {
        ports = new ArrayDeque<Integer>();
        for(Object portObj : JavaConversions.asJavaList(TestUtils.choosePorts(numPorts)))
            ports.add((Integer)portObj);
        zkPort = ports.remove();
        zkConnect = String.format("localhost:%d", zkPort);

        configs = new Vector<KafkaConfig>();
        String bootstrapServers = "";
        for(int i = 0; i < numBrokers; i++) {
            int port = ports.remove();
            Properties props = TestUtils.createBrokerConfig(i, port, false);
            // Turn auto creation *off*, unlike the default. This lets us test errors that should be generated when
            // brokers are configured that way.
            props.setProperty("auto.create.topics.enable", "false");
            // We *must* override this to use the port we allocated (Kafka currently allocates one port that it always
            // uses for ZK
            props.setProperty("zookeeper.connect", this.zkConnect);
            configs.add(new KafkaConfig(props));

            if (bootstrapServers.length() > 0)
                bootstrapServers += ",";
            bootstrapServers = bootstrapServers + "localhost:" + ((Integer)port).toString();
        }

        restProperties = new Properties();
        int restPort = ports.remove();
        restProperties.setProperty("port", ((Integer) restPort).toString());
        restProperties.setProperty("zookeeper.connect", zkConnect);
        restProperties.setProperty("bootstrap.servers", bootstrapServers);
        restConnect = String.format("http://localhost:%d", restPort);
    }

    @Before
    public void setUp() throws Exception {
        zookeeper = new EmbeddedZookeeper(zkConnect);
        zkClient = new ZkClient(zookeeper.connectString(), zkSessionTimeout, zkConnectionTimeout, ZKStringSerializer$.MODULE$);

        if(configs == null || configs.size() <= 0)
            throw new RuntimeException("Must supply at least one server config.");
        brokerList = TestUtils.getBrokerListStrFromConfigs(JavaConversions.asScalaIterable(configs).toSeq());
        servers = new Vector<KafkaServer>(configs.size());
        for(KafkaConfig config : configs) {
            KafkaServer server = TestUtils.createServer(config, SystemTime$.MODULE$);
            servers.add(server);
        }

        restServer = Main.createServer(restProperties);
        restServer.start();
    }

    @After
    public void tearDown() throws Exception {
        restServer.stop();
        restServer.join();

        for(KafkaServer server: servers)
            server.shutdown();
        for(KafkaServer server: servers)
            for (String logDir : JavaConversions.asJavaCollection(server.config().logDirs()))
                Utils.rm(logDir);

        zkClient.close();
        zookeeper.shutdown();
    }

    protected Invocation.Builder request(String path) {
        return ClientBuilder.newClient()
                .register(JacksonMessageBodyProvider.class)
                .target(restConnect).path(path)
                .request();
    }
    protected Invocation.Builder request(String path, String templateName, Object templateValue) {
        return ClientBuilder.newClient()
                .register(JacksonMessageBodyProvider.class)
                .target(restConnect).path(path)
                .resolveTemplate(templateName, templateValue)
                .request();
    }
}