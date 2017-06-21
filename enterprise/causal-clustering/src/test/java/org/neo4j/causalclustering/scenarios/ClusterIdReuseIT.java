/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.causalclustering.scenarios;


import org.apache.commons.lang3.mutable.MutableLong;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.TimeoutException;

import org.neo4j.causalclustering.discovery.Cluster;
import org.neo4j.causalclustering.discovery.CoreClusterMember;
import org.neo4j.graphdb.Node;
import org.neo4j.kernel.impl.storageengine.impl.recordstorage.id.IdController;
import org.neo4j.kernel.impl.store.id.IdGenerator;
import org.neo4j.kernel.impl.store.id.IdType;
import org.neo4j.test.causalclustering.ClusterRule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assume.assumeTrue;

public class ClusterIdReuseIT
{
    @Rule
    public final ClusterRule clusterRule = new ClusterRule( getClass() )
            .withNumberOfCoreMembers( 3 )
            .withNumberOfReadReplicas( 0 );
    private Cluster cluster;

    @Before
    public void setUp() throws Exception
    {
        cluster = clusterRule.startCluster();
    }

    @Test
    public void shouldReuseIdsInCluster() throws Exception
    {
        final MutableLong first = new MutableLong();
        final MutableLong second = new MutableLong();

        CoreClusterMember leader1 = createThreeNodes( cluster, first, second );
        CoreClusterMember leader2 = removeTwoNodes( cluster, first, second );

        assumeTrue( leader1 != null && leader1.equals( leader2 ) );

        // Force maintenance on leader
        IdController idController = idMaintenanceOnLeader( leader1 );

        final IdGenerator idGenerator = idController.getIdGeneratorFactory().get( IdType.NODE );
        assertEquals( 2, idGenerator.getDefragCount() );

        final MutableLong node1id = new MutableLong();
        final MutableLong node2id = new MutableLong();
        final MutableLong node3id = new MutableLong();

        CoreClusterMember clusterMember = cluster.coreTx( ( db, tx ) ->
        {
            Node node1 = db.createNode();
            Node node2 = db.createNode();
            Node node3 = db.createNode();

            node1id.setValue( node1.getId() );
            node2id.setValue( node2.getId() );
            node3id.setValue( node3.getId() );

            tx.success();
        } );

        assumeTrue( leader1.equals( clusterMember ) );

        assertEquals( first.longValue(), node1id.longValue() );
        assertEquals( second.longValue(), node2id.longValue() );
        assertEquals( idGenerator.getHighestPossibleIdInUse(), node3id.longValue() );
    }

    @Test
    public void newLeaderShouldNotReuseIds() throws Exception
    {
        final MutableLong first = new MutableLong();
        final MutableLong second = new MutableLong();

        CoreClusterMember creationLeader = createThreeNodes( cluster, first, second );
        CoreClusterMember deletionLeader = removeTwoNodes( cluster, first, second );

        assumeTrue( creationLeader != null && creationLeader.equals( deletionLeader ) );

        IdGenerator creationLeaderIdGenerator = idMaintenanceOnLeader( creationLeader ).getIdGeneratorFactory().get( IdType.NODE );
        assertEquals( 2, creationLeaderIdGenerator.getDefragCount() );

        // Force leader switch
        cluster.removeCoreMemberWithMemberId( creationLeader.serverId() );

        // waiting for new leader
        CoreClusterMember newLeader = cluster.awaitLeader();
        assertNotSame( creationLeader.serverId(), newLeader.serverId() );
        IdController idController = idMaintenanceOnLeader( newLeader );

        final IdGenerator idGenerator = idController.getIdGeneratorFactory().get( IdType.NODE );
        assertEquals( 0, idGenerator.getDefragCount() );

        CoreClusterMember newCreationLeader = cluster.coreTx( ( db, tx ) ->
        {
            Node node = db.createNode();
            assertEquals( idGenerator.getHighestPossibleIdInUse(), node.getId() );

            tx.success();
        } );
        assumeTrue( newLeader.equals( newCreationLeader ) );
    }

    @Test
    public void reusePreviouslyFreedIds() throws Exception
    {
        final MutableLong first = new MutableLong();
        final MutableLong second = new MutableLong();

        CoreClusterMember creationLeader = createThreeNodes( cluster, first, second );
        CoreClusterMember deletionLeader = removeTwoNodes( cluster, first, second );

        assumeTrue( creationLeader != null && creationLeader.equals( deletionLeader ) );

        IdGenerator creationLeaderIdGenerator = idMaintenanceOnLeader( creationLeader ).getIdGeneratorFactory().get( IdType.NODE );
        assertEquals( 2, creationLeaderIdGenerator.getDefragCount() );


        // Restart and re-elect first leader
        cluster.removeCoreMemberWithMemberId( creationLeader.serverId() );
        cluster.addCoreMemberWithId( creationLeader.serverId() ).start();

        CoreClusterMember leader = cluster.awaitLeader();
        while ( leader.serverId() != creationLeader.serverId() )
        {
            cluster.removeCoreMemberWithMemberId( leader.serverId() );
            cluster.addCoreMemberWithId( leader.serverId() ).start();
            leader = cluster.awaitLeader();
        }

        creationLeaderIdGenerator = idMaintenanceOnLeader( leader ).getIdGeneratorFactory().get( IdType.NODE );
        assertEquals( 2, creationLeaderIdGenerator.getDefragCount() );

        final MutableLong node1id = new MutableLong();
        final MutableLong node2id = new MutableLong();
        CoreClusterMember reuseLeader = cluster.coreTx( ( db, tx ) ->
        {
            Node node1 = db.createNode();
            Node node2 = db.createNode();

            node1id.setValue( node1.getId() );
            node2id.setValue( node2.getId() );

            tx.success();
        } );
        assumeTrue( leader.equals( reuseLeader ) );

        assertEquals( first.longValue(), node1id.longValue() );
        assertEquals( second.longValue(), node2id.longValue() );
    }

    private IdController idMaintenanceOnLeader( CoreClusterMember leader ) throws TimeoutException
    {
        IdController idController = leader.database().getDependencyResolver().resolveDependency( IdController.class );
        idController.maintenance();
        return idController;
    }

    private CoreClusterMember removeTwoNodes( Cluster cluster, MutableLong first, MutableLong second ) throws Exception
    {
        return cluster.coreTx( ( db, tx ) ->
        {
            Node node1 = db.getNodeById( first.longValue() );
            node1.delete();

            db.getNodeById( second.longValue() ).delete();

            tx.success();
        } );
    }

    private CoreClusterMember createThreeNodes( Cluster cluster, MutableLong first, MutableLong second ) throws Exception
    {
        return cluster.coreTx( ( db, tx ) ->
        {
            Node node1 = db.createNode();
            first.setValue( node1.getId() );

            Node node2 = db.createNode();
            second.setValue( node2.getId() );

            db.createNode();

            tx.success();
        } );
    }
}
