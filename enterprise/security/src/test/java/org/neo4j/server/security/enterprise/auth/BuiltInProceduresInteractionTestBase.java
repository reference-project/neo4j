/*
 * Copyright (c) 2002-2016 "Neo Technology,"
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
package org.neo4j.server.security.enterprise.auth;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.test.DoubleLatch;
import org.neo4j.test.rule.concurrent.ThreadingRule;

import static java.lang.String.format;
import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.isA;
import static org.neo4j.helpers.collection.MapUtil.map;
import static org.neo4j.server.security.enterprise.auth.AuthProcedures.PERMISSION_DENIED;
import static org.neo4j.test.matchers.CommonMatchers.matchesOneToOneInAnyOrder;

public abstract class BuiltInProceduresInteractionTestBase<S> extends ProcedureInteractionTestBase<S>
{
    @Rule
    public final ThreadingRule threading = new ThreadingRule();

    //---------- list running transactions -----------

    @Test
    public void shouldListSelfTransaction()
    {
        assertSuccess( adminSubject, "CALL dbms.security.listTransactions()",
                r -> assertKeyIsMap( r, "username", "activeTransactions", map( "adminSubject", "1" ) ) );
    }

    @Test
    public void shouldNotListTransactionsIfNotAdmin()
    {
        assertFail( noneSubject, "CALL dbms.security.listTransactions()", PERMISSION_DENIED );
        assertFail( readSubject, "CALL dbms.security.listTransactions()", PERMISSION_DENIED );
        assertFail( writeSubject, "CALL dbms.security.listTransactions()", PERMISSION_DENIED );
        assertFail( schemaSubject, "CALL dbms.security.listTransactions()", PERMISSION_DENIED );
    }

    @Test
    public void shouldListTransactions() throws Throwable
    {
        DoubleLatch latch = new DoubleLatch( 3 );
        ThreadedTransactionCreate<S> write1 = new ThreadedTransactionCreate<>( neo, latch );
        ThreadedTransactionCreate<S> write2 = new ThreadedTransactionCreate<>( neo, latch );

        String q1 = write1.execute( threading, writeSubject );
        String q2 = write2.execute( threading, writeSubject );
        latch.startAndWaitForAllToStart();

        assertSuccess( adminSubject, "CALL dbms.security.listTransactions()",
                r -> assertKeyIsMap( r, "username", "activeTransactions",
                        map( "adminSubject", "1", "writeSubject", "2" ) ) );

        latch.finishAndWaitForAllToFinish();

        write1.closeAndAssertSuccess();
        write2.closeAndAssertSuccess();
    }

    @Test
    public void shouldListRestrictedTransaction()
    {
        final DoubleLatch doubleLatch = new DoubleLatch( 2 );

        ClassWithProcedures.setTestLatch( new ClassWithProcedures.LatchedRunnables( doubleLatch, () -> {}, () -> {} ) );

        new Thread( () -> assertEmpty( writeSubject, "CALL test.waitForLatch()" ) ).start();
        doubleLatch.startAndWaitForAllToStart();
        try
        {
            assertSuccess( adminSubject, "CALL dbms.security.listTransactions()",
                    r -> assertKeyIsMap( r, "username", "activeTransactions",
                            map( "adminSubject", "1", "writeSubject", "1" ) ) );
        }
        finally
        {
            doubleLatch.finishAndWaitForAllToFinish();
        }
    }

    @SuppressWarnings( "unchecked" )
    @Test
    public void shouldListAllQueriesWhenRunningAsAdmin() throws Throwable
    {
        DoubleLatch latch = new DoubleLatch( 3, true );
        OffsetDateTime startTime = OffsetDateTime.now();

        ThreadedTransactionCreate<S> read1 = new ThreadedTransactionCreate<>( neo, latch );
        ThreadedTransactionCreate<S> read2 = new ThreadedTransactionCreate<>( neo, latch );

        String q1 = read1.execute( threading, readSubject, "UNWIND [1,2,3] AS x RETURN x" );
        String q2 = read2.execute( threading, readSubject, "UNWIND [4,5,6] AS y RETURN y" );
        latch.startAndWaitForAllToStart();

        String query = "CALL dbms.listQueries()";
        assertSuccess( adminSubject, query, r ->
        {
            Set<Map<String,Object>> maps = r.stream().collect( Collectors.toSet() );

            Matcher<Map<String,Object>> thisQuery = listedQuery( startTime, "adminSubject", query );
            Matcher<Map<String,Object>> matcher1 = listedQuery( startTime, "readSubject", q1 );
            Matcher<Map<String,Object>> matcher2 = listedQuery( startTime, "readSubject", q2 );

            assertThat( maps, matchesOneToOneInAnyOrder( matcher1, matcher2, thisQuery ) );
        } );

        latch.finishAndWaitForAllToFinish();

        read1.closeAndAssertSuccess();
        read2.closeAndAssertSuccess();
    }

    @Test
    public void shouldOnlyListOwnQueriesWhenNotRunningAsAdmin() throws Throwable
    {
        DoubleLatch latch = new DoubleLatch( 3, true );
        OffsetDateTime startTime = OffsetDateTime.now();
        ThreadedTransactionCreate<S> read1 = new ThreadedTransactionCreate<>( neo, latch );
        ThreadedTransactionCreate<S> read2 = new ThreadedTransactionCreate<>( neo, latch );

        String q1 = read1.execute( threading, readSubject, "UNWIND [1,2,3] AS x RETURN x" );
        String ignored = read2.execute( threading, writeSubject, "UNWIND [4,5,6] AS y RETURN y" );
        latch.startAndWaitForAllToStart();

        String query = "CALL dbms.listQueries()";
        assertSuccess( readSubject, query, r ->
        {
            Set<Map<String,Object>> maps = r.stream().collect( Collectors.toSet() );

            Matcher<Map<String,Object>> thisQuery = listedQuery( startTime, "readSubject", query );
            Matcher<Map<String,Object>> queryMatcher = listedQuery( startTime, "readSubject", q1 );

            assertThat( maps, matchesOneToOneInAnyOrder( queryMatcher, thisQuery ) );
        } );

        latch.finishAndWaitForAllToFinish();

        read1.closeAndAssertSuccess();
        read2.closeAndAssertSuccess();
    }

    //---------- terminate transactions for user -----------

    @Test
    public void shouldTerminateTransactionForUser() throws Throwable
    {
        DoubleLatch latch = new DoubleLatch( 2 );
        ThreadedTransactionCreate<S> write = new ThreadedTransactionCreate<>( neo, latch );
        write.execute( threading, writeSubject );
        latch.startAndWaitForAllToStart();

        assertSuccess( adminSubject, "CALL dbms.security.terminateTransactionsForUser( 'writeSubject' )",
                r -> assertKeyIsMap( r, "username", "transactionsTerminated", map( "writeSubject", "1" ) ) );

        assertSuccess( adminSubject, "CALL dbms.security.listTransactions()",
                r -> assertKeyIsMap( r, "username", "activeTransactions", map( "adminSubject", "1" ) ) );

        latch.finishAndWaitForAllToFinish();

        write.closeAndAssertTransactionTermination();

        assertEmpty( adminSubject, "MATCH (n:Test) RETURN n.name AS name" );
    }

    @Test
    public void shouldTerminateOnlyGivenUsersTransaction() throws Throwable
    {
        DoubleLatch latch = new DoubleLatch( 3 );
        ThreadedTransactionCreate<S> schema = new ThreadedTransactionCreate<>( neo, latch );
        ThreadedTransactionCreate<S> write = new ThreadedTransactionCreate<>( neo, latch );

        schema.execute( threading, schemaSubject );
        write.execute( threading, writeSubject );
        latch.startAndWaitForAllToStart();

        assertSuccess( adminSubject, "CALL dbms.security.terminateTransactionsForUser( 'schemaSubject' )",
                r -> assertKeyIsMap( r, "username", "transactionsTerminated", map( "schemaSubject", "1" ) ) );

        assertSuccess( adminSubject, "CALL dbms.security.listTransactions()",
                r ->  assertKeyIsMap( r, "username", "activeTransactions",
                        map( "adminSubject", "1", "writeSubject", "1" ) ) );

        latch.finishAndWaitForAllToFinish();

        schema.closeAndAssertTransactionTermination();
        write.closeAndAssertSuccess();

        assertSuccess( adminSubject, "MATCH (n:Test) RETURN n.name AS name",
                r -> assertKeyIs( r, "name", "writeSubject-node" ) );
    }

    @Test
    public void shouldTerminateAllTransactionsForGivenUser() throws Throwable
    {
        DoubleLatch latch = new DoubleLatch( 3 );
        ThreadedTransactionCreate<S> schema1 = new ThreadedTransactionCreate<>( neo, latch );
        ThreadedTransactionCreate<S> schema2 = new ThreadedTransactionCreate<>( neo, latch );

        schema1.execute( threading, schemaSubject );
        schema2.execute( threading, schemaSubject );
        latch.startAndWaitForAllToStart();

        assertSuccess( adminSubject, "CALL dbms.security.terminateTransactionsForUser( 'schemaSubject' )",
                r -> assertKeyIsMap( r, "username", "transactionsTerminated", map( "schemaSubject", "2" ) ) );

        assertSuccess( adminSubject, "CALL dbms.security.listTransactions()",
                r ->  assertKeyIsMap( r, "username", "activeTransactions", map( "adminSubject", "1" ) ) );

        latch.finishAndWaitForAllToFinish();

        schema1.closeAndAssertTransactionTermination();
        schema2.closeAndAssertTransactionTermination();

        assertEmpty( adminSubject, "MATCH (n:Test) RETURN n.name AS name" );
    }

    @Test
    public void shouldNotTerminateTerminationTransaction() throws InterruptedException, ExecutionException
    {
        assertSuccess( adminSubject, "CALL dbms.security.terminateTransactionsForUser( 'adminSubject' )",
                r -> assertKeyIsMap( r, "username", "transactionsTerminated", map( "adminSubject", "0" ) ) );
        assertSuccess( readSubject, "CALL dbms.security.terminateTransactionsForUser( 'readSubject' )",
                r -> assertKeyIsMap( r, "username", "transactionsTerminated", map( "readSubject", "0" ) ) );
    }

    @Test
    public void shouldTerminateSelfTransactionsExceptTerminationTransactionIfAdmin() throws Throwable
    {
        shouldTerminateSelfTransactionsExceptTerminationTransaction( adminSubject );
    }

    @Test
    public void shouldTerminateSelfTransactionsExceptTerminationTransactionIfNotAdmin() throws Throwable
    {
        shouldTerminateSelfTransactionsExceptTerminationTransaction( writeSubject );
    }

    private void shouldTerminateSelfTransactionsExceptTerminationTransaction( S subject ) throws Throwable
    {
        DoubleLatch latch = new DoubleLatch( 2 );
        ThreadedTransactionCreate<S> create = new ThreadedTransactionCreate<>( neo, latch );
        create.execute( threading, subject );

        latch.startAndWaitForAllToStart();

        String subjectName = neo.nameOf( subject );
        assertSuccess( subject, "CALL dbms.security.terminateTransactionsForUser( '" + subjectName + "' )",
                r -> assertKeyIsMap( r, "username", "transactionsTerminated", map( subjectName, "1" ) ) );

        latch.finishAndWaitForAllToFinish();

        create.closeAndAssertTransactionTermination();

        assertEmpty( adminSubject, "MATCH (n:Test) RETURN n.name AS name" );
    }

    @Test
    public void shouldNotTerminateTransactionsIfNonExistentUser() throws InterruptedException, ExecutionException
    {
        assertFail( adminSubject, "CALL dbms.security.terminateTransactionsForUser( 'Petra' )", "User 'Petra' does not exist" );
        assertFail( adminSubject, "CALL dbms.security.terminateTransactionsForUser( '' )", "User '' does not exist" );
    }

    @Test
    public void shouldNotTerminateTransactionsIfNotAdmin() throws Throwable
    {
        DoubleLatch latch = new DoubleLatch( 2 );
        ThreadedTransactionCreate<S> write = new ThreadedTransactionCreate<>( neo, latch );
        write.execute( threading, writeSubject );
        latch.startAndWaitForAllToStart();

        assertFail( noneSubject, "CALL dbms.security.terminateTransactionsForUser( 'writeSubject' )", PERMISSION_DENIED );
        assertFail( pwdSubject, "CALL dbms.security.terminateTransactionsForUser( 'writeSubject' )", CHANGE_PWD_ERR_MSG );
        assertFail( readSubject, "CALL dbms.security.terminateTransactionsForUser( 'writeSubject' )", PERMISSION_DENIED );
        assertFail( schemaSubject, "CALL dbms.security.terminateTransactionsForUser( 'writeSubject' )", PERMISSION_DENIED );

        assertSuccess( adminSubject, "CALL dbms.security.listTransactions()",
                r -> assertKeyIs( r, "username", "adminSubject", "writeSubject" ) );

        latch.finishAndWaitForAllToFinish();

        write.closeAndAssertSuccess();

        assertSuccess( adminSubject, "MATCH (n:Test) RETURN n.name AS name",
                r -> assertKeyIs( r, "name", "writeSubject-node" ) );
    }

    @Test
    public void shouldTerminateRestrictedTransaction()
    {
        final DoubleLatch doubleLatch = new DoubleLatch( 2 );

        ClassWithProcedures.setTestLatch( new ClassWithProcedures.LatchedRunnables( doubleLatch, () -> {}, () -> {} ) );

        new Thread( () -> assertFail( writeSubject, "CALL test.waitForLatch()", "Explicitly terminated by the user." ) )
                .start();

        doubleLatch.startAndWaitForAllToStart();
        try
        {
            assertSuccess( adminSubject, "CALL dbms.security.terminateTransactionsForUser( 'writeSubject' )",
                    r -> assertKeyIsMap( r, "username", "transactionsTerminated", map( "writeSubject", "1" ) ) );
        }
        finally
        {
            doubleLatch.finishAndWaitForAllToFinish();
        }
    }

    @SuppressWarnings( "unchecked" )
    @Test
    public void shouldListQueriesEvenIfUsingPeriodicCommit() throws Throwable
    {
        // Spawns a throttled HTTP server, runs a PERIODIC COMMIT that fetches data from this server,
        // and checks that the query is visible when using listQueries()

        // Given
        final DoubleLatch latch = new DoubleLatch( 2, true );
        final AtomicBoolean keepGoing = new AtomicBoolean( true );

        // Serve CSV via local web server, let Jetty find a random port for us
        Server server = createHttpServer( keepGoing );
        server.start();
        int localPort = getLocalPort(server);

        OffsetDateTime startTime = OffsetDateTime.now();

        // When
        ThreadedTransactionCreate<S> write = new ThreadedTransactionCreate<>( neo, latch );

        try
        {
            String writeQuery = write.execute( threading, writeSubject, KernelTransaction.Type.implicit, keepGoing,
                    format( "USING PERIODIC COMMIT 10 LOAD CSV FROM 'http://localhost:%d' AS line ", localPort ) +
                    "CREATE (n:A {id: line[0], square: line[1]}) " +
                    "RETURN count(n)"
            );
            latch.startAndWaitForAllToStart();

            // Then
            String query = "CALL dbms.listQueries()";
            assertSuccess( adminSubject, query, r ->
            {
                Set<Map<String,Object>> maps = r.stream().collect( Collectors.toSet() );

                Matcher<Map<String,Object>> thisMatcher = listedQuery( startTime, "adminSubject", query );
                Matcher<Map<String,Object>> writeMatcher = listedQuery( startTime, "writeSubject", writeQuery );

                assertThat( maps, hasItem( thisMatcher ) );
                assertThat( maps, hasItem( writeMatcher ) );
            } );
        }
        finally
        {
            // When
            keepGoing.set( false );
            latch.finishAndWaitForAllToFinish();
            server.stop();

            // Then
            write.closeAndAssertSuccess();
        }
    }

    private int getLocalPort( Server server )
    {
        return ((ServerConnector) (server.getConnectors()[0])).getLocalPort();

    }

    private Server createHttpServer( final AtomicBoolean keepGoing )
    {
        Server server = new Server( 0 );
        server.setHandler( new AbstractHandler()
        {
            @Override
            public void handle(
                    String target,
                    Request baseRequest,
                    HttpServletRequest request,
                    HttpServletResponse response
            ) throws IOException, ServletException
            {
                response.setContentType( "text/plain; charset=utf-8" );
                response.setStatus( HttpServletResponse.SC_OK );
                PrintWriter out = response.getWriter();

                int i = 0;
                while( keepGoing.get() )
                {
                    try
                    {
                        Thread.sleep( 25 );
                    }
                    catch ( InterruptedException e )
                    {
                        Thread.interrupted();
                    }
                    out.write( format( "%d %d\n", i, i*i ) );
                    i++;
                }
                baseRequest.setHandled(true);
            }
        } );
        return server;
    }

    //---------- matchers-----------

    private Matcher<Map<String,Object>> listedQuery( OffsetDateTime startTime, String username, String query )
    {
        return allOf(
                hasQuery( query ),
                hasUsername( username ),
                hasQueryId(),
                hasStartTimeAfter( startTime ),
                hasNoParameters()
        );
    }
    @SuppressWarnings( "unchecked" )
    private Matcher<Map<String, Object>> hasQuery( String query )
    {
        return (Matcher<Map<String, Object>>) (Matcher) hasEntry( equalTo( "query" ), equalTo( query ) );
    }

    @SuppressWarnings( "unchecked" )
    private Matcher<Map<String, Object>> hasUsername( String username )
    {
        return (Matcher<Map<String, Object>>) (Matcher) hasEntry( equalTo( "username" ), equalTo( username ) );
    }

    @SuppressWarnings( "unchecked" )
    private Matcher<Map<String, Object>> hasQueryId()
    {
        return (Matcher<Map<String, Object>>) (Matcher) hasEntry( equalTo( "queryId" ), anyOf( (Matcher) isA( Integer.class ), isA( Long.class ) ) );
    }

    @SuppressWarnings( "unchecked" )
    private Matcher<Map<String, Object>> hasStartTimeAfter( OffsetDateTime startTime )
    {
        return (Matcher<Map<String, Object>>) (Matcher) hasEntry( equalTo( "startTime" ), new BaseMatcher<String>()
        {
            @Override
            public void describeTo( Description description )
            {
                description.appendText( "should be after " + startTime.toString() );
            }

            @Override
            public boolean matches( Object item )
            {
                OffsetDateTime otherTime =  OffsetDateTime.from( ISO_OFFSET_DATE_TIME.parse( item.toString() ) );
                return startTime.compareTo( otherTime ) <= 0;
            }
        } );
    }

    @SuppressWarnings( "unchecked" )
    private Matcher<Map<String, Object>> hasNoParameters()
    {
        return (Matcher<Map<String, Object>>) (Matcher) hasEntry( equalTo( "parameters" ), equalTo( Collections.emptyMap() ) );
    }

    @Override
    protected ThreadingRule threading()
    {
        return threading;
    }
}
