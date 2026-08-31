package com.pm.stack;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.ecs.Protocol;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateService;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.msk.CfnCluster;
import software.amazon.awscdk.services.ec2.ISubnet;
import software.amazon.awscdk.services.ec2.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class LocalStack extends Stack {

    private final Vpc vpc;
    private final Cluster ecsCluster;

    public LocalStack(final App scope, final String id, final StackProps props) {
        super(scope, id, props);

        this.vpc = createVpc();

        CfnCluster mskCluster = createMskCluster();

        this.ecsCluster = createEcsCluster();

        FargateService authService =
                createFargateService(
                        "AuthService",
                        "auth-service",
                        List.of(4005),
                        Map.of(
                                "JWT_SECRET",
                                "Y2hhVEc3aHJnb0hYTzMyZ2ZqVkpiZ1RkZG93YWxrUkM=",
                                "SPRING_DATABASE_URL",
                                "jdbc:postgresql://host.docker.internal:5001/auth-service-db",
                                "SPRING_DATABASE_USERNAME",
                                "admin_user"
                        )
                );

        FargateService billingService =
                createFargateService(
                        "BillingService",
                        "billing-service",
                        List.of(4001, 9001),
                        null
                );

        FargateService analyticsService =
                createFargateService(
                        "AnalyticsService",
                        "analytics-service",
                        List.of(4002),
                        null
                );

        analyticsService.getNode().addDependency(mskCluster);

        FargateService patientService =
                createFargateService(
                        "PatientService",
                        "patient-service",
                        List.of(4000),
                        Map.of(
                                "SPRING_DATABASE_URL",
                                "jdbc:postgresql://host.docker.internal:5001/patient-service-db",
                                "SPRING_DATABASE_USERNAME",
                                "admin_user",
                                "BILLING_SERVICE_ADDRESS",
                                "host.docker.internal",
                                "BILLING_SERVICE_GRPC_PORT",
                                "9001"
                        )
                );

        patientService.getNode().addDependency(billingService);
        patientService.getNode().addDependency(mskCluster);

        createApiGatewayService();
    }

    private Vpc createVpc() {
        return Vpc.Builder.create(this, "PatientManagementVPC")
                .vpcName("PatientManagementVPC")
                .maxAzs(2)
                .build();
    }

    private CfnCluster createMskCluster() {
        return CfnCluster.Builder.create(this, "MskCluster")
                .clusterName("kafka-cluster")
                .kafkaVersion("2.8.0")
                .numberOfBrokerNodes(1)
                .brokerNodeGroupInfo(
                        CfnCluster.BrokerNodeGroupInfoProperty.builder()
                                .instanceType("kafka.m5.xlarge")
                                .clientSubnets(
                                        vpc.getPrivateSubnets().stream()
                                                .map(ISubnet::getSubnetId)
                                                .collect(Collectors.toList())
                                )
                                .brokerAzDistribution("DEFAULT")
                                .build()
                )
                .build();
    }

    private Cluster createEcsCluster() {
        return Cluster.Builder.create(this, "PatientManagementCluster")
                .vpc(vpc)
                .defaultCloudMapNamespace(CloudMapNamespaceOptions.builder()
                        .name("patient-management.local")
                        .build())
                .build();
    }

    private FargateService createFargateService(
            String id,
            String imageName,
            List<Integer> ports,
            Map<String, String> additionalEnvVars) {

        FargateTaskDefinition taskDefinition =
                FargateTaskDefinition.Builder.create(this, id + "Task")
                        .cpu(256)
                        .memoryLimitMiB(512)
                        .build();

        ContainerDefinitionOptions.Builder containerOptions =
                ContainerDefinitionOptions.builder()
                        .image(ContainerImage.fromRegistry(imageName))
                        .portMappings(
                                ports.stream()
                                        .map(port -> PortMapping.builder()
                                                .containerPort(port)
                                                .hostPort(port)
                                                .protocol(Protocol.TCP)
                                                .build())
                                        .toList()
                        )
                        .logging(
                                LogDriver.awsLogs(
                                        AwsLogDriverProps.builder()
                                                .logGroup(
                                                        LogGroup.Builder.create(
                                                                        this,
                                                                        id + "LogGroup")
                                                                .logGroupName("/ecs/" + imageName)
                                                                .removalPolicy(RemovalPolicy.DESTROY)
                                                                .retention(RetentionDays.ONE_DAY)
                                                                .build()
                                                )
                                                .streamPrefix(imageName)
                                                .build()
                                )
                        );

        Map<String, String> envVars = new HashMap<>();

        envVars.put(
                "SPRING_KAFKA_BOOTSTRAP_SERVERS",
                "localhost.localstack.cloud:4510,localhost.localstack.cloud:4511,localhost.localstack.cloud:4512"
        );

        if (additionalEnvVars != null) {
            envVars.putAll(additionalEnvVars);
        }

        containerOptions.environment(envVars);

        taskDefinition.addContainer(
                imageName + "Container",
                containerOptions.build()
        );

        return FargateService.Builder.create(this, id)
                .cluster(ecsCluster)
                .taskDefinition(taskDefinition)
                .assignPublicIp(false)
                .serviceName(imageName)
                .build();
    }

    private void createApiGatewayService() {

        FargateTaskDefinition taskDefinition =
                FargateTaskDefinition.Builder.create(this, "ApiGatewayTaskDefinition")
                        .cpu(256)
                        .memoryLimitMiB(512)
                        .build();

        ContainerDefinitionOptions containerOptions =
                ContainerDefinitionOptions.builder()
                        .image(ContainerImage.fromRegistry("api-gateway"))
                        .environment(Map.of(
                                "SPRING_PROFILES_ACTIVE", "prod",
                                "AUTH_SERVICE_URL", "http://host.docker.internal:4005"
                        ))
                        .portMappings(List.of(4004).stream()
                                .map(port -> PortMapping.builder()
                                        .containerPort(port)
                                        .hostPort(port)
                                        .protocol(Protocol.TCP)
                                        .build())
                                .toList())
                        .logging(LogDriver.awsLogs(AwsLogDriverProps.builder()
                                .logGroup(LogGroup.Builder.create(this, "ApiGatewayLogGroup")
                                        .logGroupName("/ecs/api-gateway")
                                        .removalPolicy(RemovalPolicy.DESTROY)
                                        .retention(RetentionDays.ONE_DAY)
                                        .build())
                                .streamPrefix("api-gateway")
                                .build()))
                .build();

        taskDefinition.addContainer("APIGatewayContainer", containerOptions);

        ApplicationLoadBalancedFargateService apiGateway =
                ApplicationLoadBalancedFargateService.Builder.create(this, "APIGatewayService")
                        .cluster(ecsCluster)
                        .serviceName("api-gateway")
                        .taskDefinition(taskDefinition)
                        .desiredCount(1)
                        .healthCheckGracePeriod(Duration.seconds(60))
                        .build();
    }

    public static void main(String[] args) {
        App app = new App(AppProps.builder().outdir("./cdk.out").build());

        StackProps props = StackProps.builder()
                .synthesizer(new BootstraplessSynthesizer())
                .build();

        new LocalStack(app, "localstack", props);
        app.synth();
        System.out.println("App Synthesizing in progress...");
    }
}
