/*******************************************************************************
 * Copyright (c) 2023 Christoph Läubrich and others.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Christoph Läubrich - initial API and implementation
 *******************************************************************************/
package org.eclipse.tycho.core.osgitools;

import java.io.File;
import java.util.Optional;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.equinox.spi.p2.publisher.PublisherHelper;
import org.eclipse.tycho.ArtifactKey;
import org.eclipse.tycho.ArtifactType;
import org.eclipse.tycho.DefaultArtifactKey;
import org.eclipse.tycho.DependencyResolutionException;
import org.eclipse.tycho.IllegalArtifactReferenceException;
import org.eclipse.tycho.MavenArtifactKey;
import org.eclipse.tycho.ResolvedArtifactKey;
import org.eclipse.tycho.TargetPlatform;
import org.eclipse.tycho.core.TychoProjectManager;
import org.eclipse.tycho.core.maven.MavenDependenciesResolver;
import org.eclipse.tycho.core.utils.TychoProjectUtils;

/**
 * {@link MavenBundleResolver} helps in gathering bundles that are living in P2 and maven world and
 * where we want to provide the user with the maximum flexibility, mostly in cases where a bundle is
 * required for technical reasons, e.g. some standard OSGi annotations where we know they are
 * available from some maven coordinates Tycho uses that information to automatically fetch the
 * artifact if it is not present in the target.
 *
 */
@Component(role = MavenBundleResolver.class)
public class MavenBundleResolver {

    @Requirement
    private Logger logger;

    @Requirement
    private TychoProjectManager projectManager;

    @Requirement
    private MavenDependenciesResolver dependenciesResolver;

    /**
     * Resolve the specified {@link MavenArtifactKey} from the target platform first and then using
     * Maven if not found there.
     * 
     * @param project
     * @param mavenSession
     * @param mavenArtifactKey
     * @return an optional describing the {@link ResolvedArtifactKey}
     */
    public Optional<ResolvedArtifactKey> resolveMavenBundle(MavenProject project, MavenSession mavenSession,
            MavenArtifactKey mavenArtifactKey) {
        if (project == null) {
            return Optional.empty();
        }
        TargetPlatform tp = TychoProjectUtils.getTargetPlatformIfAvailable(DefaultReactorProject.adapt(project));
        String type = mavenArtifactKey.getType();
        String resolvedType = PublisherHelper.CAPABILITY_NS_JAVA_PACKAGE.equals(type) ? ArtifactType.TYPE_ECLIPSE_PLUGIN
                : type;
        //target platform first...
        if (tp != null) {
            try {
                ArtifactKey resolvedArtifact = tp.resolveArtifact(type, mavenArtifactKey.getId(),
                        mavenArtifactKey.getVersion());
                File location = tp.getArtifactLocation(
                        new DefaultArtifactKey(resolvedType, resolvedArtifact.getId(), resolvedArtifact.getVersion()));
                if (location != null) {
                    return Optional.of(ResolvedArtifactKey.of(resolvedType, resolvedArtifact.getId(),
                            resolvedArtifact.getVersion(), location));
                }
            } catch (DependencyResolutionException | IllegalArtifactReferenceException e) {
                logger.debug("Cannot find key " + mavenArtifactKey + " in target platform " + e + ", trying Maven now");
            }
        }
        // then fallback to maven artifact ...
        if (mavenSession == null) {
            return Optional.empty();
        }
        String groupId = mavenArtifactKey.getGroupId();
        String artifactId = mavenArtifactKey.getArtifactId();
        try {
            Dependency dependency = new Dependency();
            dependency.setGroupId(groupId);
            dependency.setArtifactId(artifactId);
            dependency.setVersion(mavenArtifactKey.getVersion());
            Artifact artifact = dependenciesResolver.resolveHighestVersion(project, mavenSession, dependency);
            if (artifact == null) {
                return Optional.empty();
            }
            ArtifactKey artifactKey = projectManager.getArtifactKey(artifact);
            return Optional.of(ResolvedArtifactKey.of(resolvedType, artifactKey.getId(), artifactKey.getVersion(),
                    artifact.getFile()));
        } catch (VersionRangeResolutionException | ArtifactResolutionException e) {
            logger.debug("Cannot find Maven artifact " + groupId + ":" + artifactId + " for "
                    + mavenArtifactKey.getType() + " with ID " + mavenArtifactKey.getId() + " and version "
                    + mavenArtifactKey.getVersion() + ": " + e);
        }
        return Optional.empty();
    }

    public Optional<ResolvedArtifactKey> resolveMavenBundle(MavenProject project, MavenSession mavenSession,
            String groupId, String artifactId, String version) {
        try {
            Artifact artifact = dependenciesResolver.resolveArtifact(project, mavenSession, groupId, artifactId,
                    version);
            ArtifactKey artifactKey = projectManager.getArtifactKey(artifact);
            return Optional.of(ResolvedArtifactKey.of(ArtifactType.TYPE_ECLIPSE_PLUGIN, artifactKey.getId(),
                    artifactKey.getVersion(), artifact.getFile()));
        } catch (ArtifactResolutionException e) {
            return Optional.empty();
        }
    }

}
