/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
 */

package org.apache.maven.repository.mercury;

import java.util.List;

import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.mercury.artifact.ArtifactMetadata;
import org.apache.maven.mercury.artifact.ArtifactQueryList;
import org.apache.maven.mercury.artifact.ArtifactScopeEnum;
import org.apache.maven.mercury.artifact.MetadataTreeNode;
import org.apache.maven.mercury.builder.api.DependencyProcessor;
import org.apache.maven.mercury.plexus.PlexusMercury;
import org.apache.maven.mercury.repository.api.Repository;
import org.apache.maven.mercury.repository.api.RepositoryException;
import org.apache.maven.mercury.util.Util;
import org.apache.maven.repository.LegacyRepositorySystem;
import org.apache.maven.repository.MetadataGraph;
import org.apache.maven.repository.MetadataResolutionRequest;
import org.apache.maven.repository.MetadataResolutionResult;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.lang.DefaultLanguage;
import org.codehaus.plexus.lang.Language;

/**
 * @author Oleg Gusakov
 * @version $Id$
 */
@Component( role = RepositorySystem.class, hint = "mercury" )
public class MercuryRepositorySystem
    extends LegacyRepositorySystem
    implements RepositorySystem
{
    private static final Language LANG = new DefaultLanguage( MercuryRepositorySystem.class );

    @Requirement( hint = "maven" )
    DependencyProcessor _dependencyProcessor;

    @Requirement
    PlexusMercury _mercury;

    @Requirement
    ArtifactFactory _artifactFactory;

    @Override
    public ArtifactResolutionResult resolve( ArtifactResolutionRequest request )
    {
        if ( request == null )
            throw new IllegalArgumentException( LANG.getMessage( "null.request" ) );

System.out.println("mercury: request for "+request.getArtifact()
+"("+request.getArtifactDependencies()+") repos="+request.getRemoteRepostories().size()
+" repos, map=" + request.getManagedVersionMap() 
);

        if ( request.getArtifact() == null )
            throw new IllegalArgumentException( LANG.getMessage( "null.request.artifact" ) );

        ArtifactResolutionResult result = new ArtifactResolutionResult();

        List<Repository> repos =
            MercuryAdaptor.toMercuryRepos(   request.getLocalRepository()
                                           , request.getRemoteRepostories()
                                           , _dependencyProcessor
                                         );

        try
        {
long start = System.currentTimeMillis();
            org.apache.maven.artifact.Artifact mavenRootArtifact = request.getArtifact();
            org.apache.maven.artifact.Artifact mavenPluginArtifact = mavenRootArtifact;
            
            boolean isPlugin = "maven-plugin".equals( mavenRootArtifact.getType() ); 
            
            ArtifactScopeEnum scope = MercuryAdaptor.extractScope( mavenRootArtifact, isPlugin, request.getFilter() );
            
            if( isPlugin  )
                mavenRootArtifact = createArtifact( mavenRootArtifact.getGroupId()
                                                    , mavenRootArtifact.getArtifactId()
                                                    , mavenRootArtifact.getVersion()
                                                    , mavenRootArtifact.getScope()
                                                    , "jar"
                                                  );

            ArtifactMetadata rootMd = MercuryAdaptor.toMercuryMetadata( mavenRootArtifact );
            
            List<ArtifactMetadata> mercuryMetadataList = _mercury.resolve( repos, scope,  rootMd );

            List<org.apache.maven.mercury.artifact.Artifact> mercuryArtifactList =
                _mercury.read( repos, mercuryMetadataList );

long diff = System.currentTimeMillis() - start;

            org.apache.maven.artifact.Artifact root = null;
            
            if ( !Util.isEmpty( mercuryArtifactList ) )
            {
                for ( org.apache.maven.mercury.artifact.Artifact a : mercuryArtifactList )
                {
                    if( a.getGroupId().equals( rootMd.getGroupId() ) && a.getArtifactId().equals( rootMd.getArtifactId() ) )
                    { // root artifact processing
                        root = isPlugin ? mavenPluginArtifact : mavenRootArtifact;
                        
                        root.setFile( a.getFile() );
                        root.setResolved( true );
                        root.setResolvedVersion( a.getVersion() );

                        result.addArtifact( root );
                    }
                    else
                    {
                        result.addArtifact( MercuryAdaptor.toMavenArtifact( _artifactFactory, a ) );
                    }
                }

System.out.println("mercury: resolved("+diff+") "+root+"("+scope+") as file "+root.getFile() );
            }
            else
            {
                result.addMissingArtifact( mavenRootArtifact );
System.out.println("mercury: missing artifact("+diff+") "+mavenRootArtifact+"("+scope+")" );
            }
            
        }
        catch ( RepositoryException e )
        {
            result.addErrorArtifactException( new ArtifactResolutionException( e.getMessage(), request.getArtifact(),
                                                                               request.getRemoteRepostories() ) );
        }

        return result;
    }
    
    

//    public List<ArtifactVersion> retrieveAvailableVersions( Artifact artifact, ArtifactRepository localRepository,
//                                                            List<ArtifactRepository> remoteRepositories )
//        throws ArtifactMetadataRetrievalException
//    {
//
//        List<Repository> repos =
//            MercuryAdaptor.toMercuryRepos( localRepository, remoteRepositories, _dependencyProcessor );
//        
//        try
//        {
//            List<ArtifactBasicMetadata> vl = _mercury.readVersions( repos, MercuryAdaptor.toMercuryBasicMetadata( artifact ) );
//            
//            if( Util.isEmpty( vl ) )
//                return null;
//            
//            List<ArtifactVersion> res = new ArrayList<ArtifactVersion>( vl.size() );
//            
//            for( ArtifactBasicMetadata bmd : vl )
//                res.add( new DefaultArtifactVersion(bmd.getVersion()) );
//            
//            return res;
//        }
//        catch ( RepositoryException e )
//        {
//            throw new ArtifactMetadataRetrievalException(e);
//        }
//    }
    

    public MetadataResolutionResult resolveMetadata( MetadataResolutionRequest request )
    {
        if ( request == null )
            throw new IllegalArgumentException( LANG.getMessage( "null.request" ) );

        if ( request.getArtifactMetadata() == null )
            throw new IllegalArgumentException( LANG.getMessage( "null.request.artifact" ) );

        List<Repository> repos =
            MercuryAdaptor.toMercuryRepos( request.getLocalRepository()
                                           , request.getRemoteRepostories()
                                           , _dependencyProcessor
                                         );

        MetadataResolutionResult res = new MetadataResolutionResult();
        
        ArtifactMetadata md = MercuryAdaptor.toMercuryArtifactMetadata( request.getArtifactMetadata() );
        
        try
        {
            MetadataTreeNode root = _mercury.resolveAsTree( repos, ArtifactScopeEnum.valueOf( request.getScope() ), new ArtifactQueryList(md), null, null );
            if( root != null )
            {
                MetadataGraph resTree = MercuryAdaptor.resolvedTreeToGraph( root );
                
                res.setResolvedTree( resTree );
            }
        }
        catch ( RepositoryException e )
        {
            res.addError( e );
        }
        
        return res;
    }

}