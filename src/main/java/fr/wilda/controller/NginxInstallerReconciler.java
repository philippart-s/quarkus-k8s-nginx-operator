package fr.wilda.controller;

import java.io.IOException;
import java.io.InputStream;

import fr.wilda.resource.NginxInstallerResource;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;

@ControllerConfiguration
public class NginxInstallerReconciler implements Reconciler<NginxInstallerResource> {
  
    // K8S API utility
    private KubernetesClient k8sClient;
    // Watcher to do some actions when events occurs
    private Watch watch = null;
    
    public NginxInstallerReconciler(KubernetesClient k8sClient) {
        this.k8sClient = k8sClient;
    }

    @Override
    public UpdateControl<NginxInstallerResource> reconcile(NginxInstallerResource resource, Context context) {
        System.out.println("🛠️  Create / update Nginx resource operator ! 🛠️");

        String namespace = resource.getMetadata().getNamespace();

        // Load the Nginx deployment
        Deployment deployment = loadYaml(Deployment.class, "/k8s/nginx-deployment.yml");

        // Apply the number of replicas
        deployment.getSpec().setReplicas(resource.getSpec().getReplicas());
        deployment.getMetadata().setNamespace(namespace);
        // Create or update the modifications
        k8sClient.apps().deployments().inNamespace(namespace).createOrReplace(deployment);

        // Watch events on the Nginx deployment
        watch = k8sClient.apps().deployments().withName(deployment.getMetadata().getName())
                .watch(new Watcher<Deployment>() {
                    @Override
                    public void eventReceived(Action action, Deployment resource) {
                        System.out.println("⚡ Event receive on watcher ! ⚡ ➡️ " + action.name());

                        if (action == Action.DELETED) {
                            System.out.println("🗑️  Deployment deleted, recreate it ! 🗑️");
                            k8sClient.apps().deployments().inNamespace(resource.getMetadata().getNamespace())
                                    .createOrReplace(deployment);
                        }
                    }

                    @Override
                    public void onClose(WatcherException cause) {
                        System.out.println("☠️ Watcher closed due to unexpected error : " + cause);
                    }
                });

        // Create service
        Service service = loadYaml(Service.class, "/k8s/nginx-service.yml");
        k8sClient.services().inNamespace(namespace).createOrReplace(service);

        return UpdateControl.updateResource(resource);
    }

    @Override
    public DeleteControl cleanup(NginxInstallerResource resource, Context context) {
        System.out.println("💀 Delete Nginx resource operator ! 💀");

        // Avoid the automatic recreation
        if (watch != null) watch.close();
        // Delete deployment and its PODs
        k8sClient.apps().deployments().inNamespace(resource.getMetadata().getNamespace()).delete();
        // Delete the service
        k8sClient.services().inNamespace((resource.getMetadata().getNamespace())).delete();

        return DeleteControl.defaultDelete();
    }

    /**
     *  Load a YAML file and transform it to a Java class.
     * 
     * @param clazz The java class to create
     * @param yamlPath The yaml file path in the classpath
     */
    private <T> T loadYaml(Class<T> clazz, String yamlPath) {
        try (InputStream is = getClass().getResourceAsStream(yamlPath)) {
          return Serialization.unmarshal(is, clazz);
        } catch (IOException ex) {
          throw new IllegalStateException("Cannot find yaml on classpath: " + yamlPath);
        }
    }

}
