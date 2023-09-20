package com.puravida

import com.puravida.models.V1Swagger
import com.puravida.models.V1SwaggerList
import groovy.util.logging.Slf4j
import io.kubernetes.client.custom.IntOrString
import io.kubernetes.client.extended.controller.reconciler.Request
import io.kubernetes.client.extended.controller.reconciler.Result
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.AppsV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.apis.CustomObjectsApi
import io.kubernetes.client.openapi.models.V1ConfigMapBuilder
import io.kubernetes.client.openapi.models.V1ConfigMapVolumeSourceBuilder
import io.kubernetes.client.openapi.models.V1Container
import io.kubernetes.client.openapi.models.V1ContainerBuilder
import io.kubernetes.client.openapi.models.V1DeploymentBuilder
import io.kubernetes.client.openapi.models.V1KeyToPathBuilder
import io.kubernetes.client.openapi.models.V1OwnerReference
import io.kubernetes.client.openapi.models.V1OwnerReferenceBuilder
import io.kubernetes.client.openapi.models.V1ServiceBuilder
import io.kubernetes.client.openapi.models.V1Volume
import io.kubernetes.client.openapi.models.V1VolumeBuilder
import io.kubernetes.client.openapi.models.V1VolumeMount
import io.kubernetes.client.openapi.models.V1VolumeMountBuilder
import io.kubernetes.client.util.ModelMapper
import io.micronaut.core.annotation.NonNull

import io.micronaut.kubernetes.client.informer.Informer
import io.micronaut.kubernetes.client.operator.Operator
import io.micronaut.kubernetes.client.operator.OperatorResourceLister
import io.micronaut.kubernetes.client.operator.ResourceReconciler

import java.time.Instant

@Operator(
        informer = @Informer(
                apiType = V1Swagger,
                apiListType = V1SwaggerList,
                apiGroup = V1SwaggerWrapper.GROUP,
                resourcePlural = V1SwaggerWrapper.PLURAL,
                resyncCheckPeriod = 10000L
        )
)
@Slf4j
class SwaggerOperator implements ResourceReconciler<V1Swagger>{

        private final CustomObjectsApi customApi
        private final CoreV1Api coreApi
        private final AppsV1Api appsApi

        public static final String CONFIG_YML = "swagger-initializer.js";

        SwaggerOperator(CustomObjectsApi customApi, CoreV1Api coreApi, AppsV1Api appsApi) {
                this.customApi = customApi
                this.coreApi = coreApi
                this.appsApi = appsApi
                ModelMapper.addModelMap(
                        V1SwaggerWrapper.GROUP,
                        V1SwaggerWrapper.API_VERSION,
                        V1SwaggerWrapper.KIND,
                        V1SwaggerWrapper.PLURAL,
                        V1Swagger,
                        V1SwaggerList,
                )
        }

        @Override
        Result reconcile(@NonNull Request request, @NonNull OperatorResourceLister<V1Swagger> lister) {
                try {
                        log.info("reconcile $request")
                        def swaggerOptional = lister.get(request)
                        if (swaggerOptional.present) {
                                def obj = swaggerOptional.get()
                                V1SwaggerWrapper wrapper = new V1SwaggerWrapper( swaggerOptional.get() )
                                if (wrapper.beingDeleted) {
                                        return deleteResource(wrapper)
                                } else {
                                        return createOrUpdateResource(wrapper)
                                }
                        }
                        new Result(false)
                }catch( ApiException apiException){
                        log.error("ApiException $apiException.code, $apiException.message \n$apiException.responseBody",apiException)
                        new Result(false)
                }catch(Throwable e){
                        log.error(e.toString(), e)
                        new Result(false)
                }
        }

        Result deleteResource(V1SwaggerWrapper wrapper){
                log.info("Removing $wrapper.name")

                deleteConfigMap(wrapper)

                deleteDeployment(wrapper)

                deleteService(wrapper)

                wrapper.metadata.finalizers.clear();
                customApi.replaceNamespacedCustomObject(
                        V1SwaggerWrapper.GROUP,
                        V1SwaggerWrapper.API_VERSION,
                        wrapper.namespace,
                        V1SwaggerWrapper.PLURAL,
                        wrapper.name,
                        wrapper.resource,
                        null, null)
                new Result(false)
        }

        private void deleteConfigMap(V1SwaggerWrapper wrapper){
                try{
                        log.info("Removing configmap $wrapper.configMap")
                        coreApi.deleteNamespacedConfigMap(
                                wrapper.configMap,
                                wrapper.namespace,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)
                }catch (e){
                        log.error("Error deleting configMap $wrapper.configMap", e)
                }
        }

        private void deleteDeployment(V1SwaggerWrapper wrapper){
                try{
                        log.info("Removing deployment $wrapper.deployment")
                        appsApi.deleteNamespacedDeployment(
                                wrapper.deployment,
                                wrapper.namespace,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)
                }catch (e){
                        log.error("Error deleting deployment $wrapper.deployment", e)
                }
        }

        private void deleteService(V1SwaggerWrapper wrapper){
                try{
                        log.info("Removing service $wrapper.serviceName")
                        coreApi.deleteNamespacedService(
                                wrapper.serviceName,
                                wrapper.namespace,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)
                }catch (e){
                        log.error("Error deleting service $wrapper.serviceName", e)
                }
        }

        Result createOrUpdateResource(V1SwaggerWrapper wrapper){

                def services = coreApi.listNamespacedService(
                        wrapper.namespace,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null).items.findAll({service->
                        service.metadata.annotations?.containsKey(wrapper.serviceSelector)
                })

                def map = services.inject([:],{ map, it ->
                        map[it.metadata.name] = it.metadata.annotations[wrapper.serviceSelector]
                        map
                }) as Map<String, String>

                log.info "Current services $map"

                def changed = createOrUpdateConfigMap(wrapper, map)
                log.info "Map changed $changed"

                changed = createOrUpdateDeployment(wrapper, changed) ?: changed
                log.info "Deployment changed $changed"

                changed = createOrUpdateService(wrapper) ?: changed
                log.info "Service changed $changed"

                if( changed)
                        return markAsResolved(wrapper)
                else
                        return new Result(false)
        }

        Result markAsResolved(V1SwaggerWrapper wrapper){
                wrapper.reconciled()
                customApi.replaceNamespacedCustomObject(
                        V1SwaggerWrapper.GROUP,
                        V1SwaggerWrapper.API_VERSION,
                        wrapper.namespace,
                        V1SwaggerWrapper.PLURAL,
                        wrapper.name,
                        wrapper.resource,
                        null,
                        null)
                new Result(false)
        }

        private boolean createOrUpdateConfigMap(V1SwaggerWrapper wrapper, Map<String, String> services) throws ApiException {
                log.info "createOrUpdateConfigMap for $wrapper.name"

                def urlServices = services.collect {
                        "{name:'$it.key',url:'$it.value'}"
                }.join(',')

                def configMapList = coreApi
                        .listNamespacedConfigMap(wrapper.namespace,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)

                if( configMapList.items.find{ it.metadata.name == wrapper.configMap} ){

                        def configMap = coreApi
                                .readNamespacedConfigMap(wrapper.configMap,
                                        wrapper.namespace,null,null,null)

                        def currentJS = configMap.data[CONFIG_YML]

                        if( currentJS.contains("urls: [$urlServices]") ){
                                return false
                        }

                        currentJS = currentJS.replaceFirst(/urls: \[(.*?)]/,"urls: [$urlServices]")
                        configMap.data[CONFIG_YML] = currentJS

                        coreApi.replaceNamespacedConfigMap(wrapper.configMap,
                                wrapper.namespace,
                                configMap,
                                null,
                                null,
                                null);

                        log.info "ConfigMap updated"
                }else{
                        def currentJS = this.getClass().getResourceAsStream("/k8s/swagger-initializer.js").text

                        currentJS = currentJS.replaceFirst(/urls: \[(.*?)]/,"urls: [$urlServices]")

                        def configMap = new V1ConfigMapBuilder()
                                .withNewMetadata()
                                .withName(wrapper.configMap)
                                .withLabels(configLabels(wrapper))
                                .addToOwnerReferences(ownerReference(wrapper))
                                .endMetadata()
                                .withData(Map.of(CONFIG_YML, currentJS))
                                .build();

                        coreApi.createNamespacedConfigMap(wrapper.namespace,
                                configMap,
                                null,
                                null,
                                null)

                        log.info("Created configMap {}", configMap.metadata.name);
                }

                return true
        }

        private boolean createOrUpdateDeployment(V1SwaggerWrapper wrapper, boolean reboot) throws ApiException {
                log.info "createOrUpdateDeployment for $wrapper.name $wrapper.deployment"

                def deploymentList = appsApi
                        .listNamespacedDeployment(wrapper.namespace,
                                null,
                                null,
                                null,
                                "metadata.name=$wrapper.deployment",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)

                if( deploymentList.items.size() ) {
                        if( !reboot ){
                                return false
                        }

                        // delete and create again
                        def deployment = deploymentList.items.first()
                        deployment.spec
                                .template
                                .metadata
                                .annotations[V1SwaggerWrapper.RESTARTED_AT_ANNOTATION]=Instant.now().toString()

                        appsApi.replaceNamespacedDeployment(deployment.metadata.name,
                                deployment.metadata.namespace,
                                deployment,
                                null,
                                null,
                                null)

                        return true
                }

                def deployment = new V1DeploymentBuilder()
                        .withNewMetadata()
                                .withName(wrapper.deployment)
                                .withLabels(deploymentLabels(wrapper))
                                .addToOwnerReferences(ownerReference(wrapper))
                        .endMetadata()
                        .withNewSpec()
                                .withReplicas(1)
                                .withNewSelector()
                                        .addToMatchLabels(deploymentLabels(wrapper))
                                .endSelector()
                                .withNewTemplate()
                                        .withNewMetadata()
                                                .withAnnotations(deploymentAnnotations(wrapper))
                                                .withLabels(deploymentLabels(wrapper))
                                        .endMetadata()
                                        .withNewSpec()
                                                .addToContainers(buildContainer(wrapper, buildVolumesMount()))
                                                .addToVolumes(buildVolume(wrapper))
                                        .endSpec()
                                .endTemplate()
                        .endSpec()
                        .build();

                appsApi.createNamespacedDeployment(wrapper.namespace, deployment, null, null, null)
                log.info "deployment for $wrapper.name created"
                true
        }

        private static Map<String, String> configLabels(V1SwaggerWrapper v1SwaggerExt){
                Map.of(
                        "app.kubernetes.io/name", v1SwaggerExt.name,
                        "app", v1SwaggerExt.name,
                        "app.kubernetes.io/version", "1.0"
                )
        }

        private static Map<String, String> deploymentLabels(V1SwaggerWrapper v1SwaggerExt){
                Map.of(
                        "app.kubernetes.io/name", v1SwaggerExt.deployment,
                        "app", v1SwaggerExt.deployment,
                        "app.kubernetes.io/version", "1.0"
                )
        }

        private static Map<String, String> deploymentAnnotations(V1SwaggerWrapper v1SwaggerExt){
                Map.of(V1SwaggerWrapper.CREATED_AT_ANNOTATION, Instant.now().toString())
        }


        private static V1OwnerReference ownerReference(V1SwaggerWrapper v1SwaggerExt) {
                new V1OwnerReferenceBuilder()
                        .withName(v1SwaggerExt.name)
                        .withApiVersion(V1SwaggerWrapper.API_VERSION)
                        .withKind(V1SwaggerWrapper.KIND)
                        .withController(Boolean.TRUE)
                        .withUid(v1SwaggerExt.metadata.uid)
                        .withBlockOwnerDeletion(Boolean.TRUE)
                        .build();
        }

        private static V1Volume buildVolume(V1SwaggerWrapper v1SwaggerExt){
                new V1VolumeBuilder()
                        .withName("swagger-config")
                        .withConfigMap(new V1ConfigMapVolumeSourceBuilder()
                                .withName(v1SwaggerExt.configMap)
                                .withItems([
                                        new V1KeyToPathBuilder()
                                                .withKey("swagger-initializer.js")
                                                .withPath("swagger-initializer.js")
                                                .build()
                                ])
                                .build()
                        )
                        .build()

        }

        private static List<V1VolumeMount> buildVolumesMount(){
                return [
                        new V1VolumeMountBuilder()
                                .withMountPath("/usr/share/nginx/html/swagger-initializer.js")
                                .withName("swagger-config")
                                .withSubPath("swagger-initializer.js")
                                .build()
                ]
        }

        private static V1Container buildContainer(V1SwaggerWrapper v1SwaggerExt, List<V1VolumeMount>volumes) {
                return new V1ContainerBuilder()
                        .withImage("swaggerapi/swagger-ui")
                                .withImagePullPolicy("IfNotPresent")
                                .withName(v1SwaggerExt.name)
                        .addNewPort()
                                .withContainerPort(8080)
                                .withName("http")
                                .withProtocol("TCP")
                        .endPort()
                        .addNewEnv()
                                .withName("KUBERNETES_NAMESPACE")
                                .withNewValueFrom()
                                        .withNewFieldRef()
                                                .withFieldPath("metadata.namespace")
                                        .endFieldRef()
                                .endValueFrom()
                        .endEnv()
                        .withVolumeMounts(volumes)
                        .build();
        }

        private boolean createOrUpdateService(V1SwaggerWrapper wrapper) throws ApiException{
                log.info "createOrUpdateService for $wrapper.name $wrapper.serviceName"
                def service = coreApi.listNamespacedService(
                        wrapper.namespace,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)
                        .items
                        .find({service->
                                service.metadata.name == wrapper.serviceName
                        })

                if( !service ){
                        def deployment = new V1ServiceBuilder()
                                .withNewMetadata()
                                        .withName(wrapper.serviceName)

                                .endMetadata()
                                .withNewSpec()
                                        .withType("ClusterIP")
                                        .addNewPort()
                                                .withProtocol("TCP")
                                                .withName("http")
                                                .withTargetPort( new IntOrString("http"))
                                                .withPort(8080)
                                        .endPort()
                                        .addToSelector("app.kubernetes.io/name",wrapper.deployment)
                                .endSpec()
                                .build();

                        coreApi.createNamespacedService(wrapper.namespace, deployment, null, null, null)
                        log.info "service for $wrapper.name created"
                }
                return true

        }
}
