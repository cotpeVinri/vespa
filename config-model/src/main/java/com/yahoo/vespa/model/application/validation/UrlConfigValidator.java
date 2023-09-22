// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.application.validation;

import com.yahoo.config.model.deploy.DeployState;
import com.yahoo.vespa.model.VespaModel;
import com.yahoo.vespa.model.container.ApplicationContainerCluster;

import java.util.Optional;

/**
 * Validates that config using s3:// urls is used in public system and with nodes that are exclusive.
 *
 * @author hmusum
 */
public class UrlConfigValidator extends Validator {

    @Override
    public void validate(VespaModel model, DeployState state) {
        if (! state.isHostedTenantApplication(model.getAdmin().getApplicationType())) return;

        model.getContainerClusters().forEach((__, cluster) -> {
            var isExclusive = model.hostSystem().getHosts()
                    .stream()
                    .map(hostResource -> hostResource.spec().membership())
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(membership -> membership.cluster().id().equals(cluster.id()))
                    .anyMatch(membership -> membership.cluster().isExclusive());

            if (! isExclusive)
                validateS3UlsInConfig(state, cluster);
        });
    }

    private static void validateS3UlsInConfig(DeployState state, ApplicationContainerCluster cluster) {
        var match = state.getFileRegistry().export().stream()
                .filter(fileReference -> fileReference.relativePath.startsWith("s3://"))
                .findFirst();

        if (match.isPresent()) {
            // TODO: Would be even better if we could add which config/field the url is set for in the error message
            String message = "Found s3:// urls in config for container cluster " + cluster.getName();
            if (state.zone().system().isPublic())
                throw new IllegalArgumentException(message + ". Nodes in the cluster need to be 'exclusive'," +
                                                           " see https://cloud.vespa.ai/en/reference/services#nodes");
            else
                throw new IllegalArgumentException(message + ". This is only supported in public systems");
        }
    }

}
