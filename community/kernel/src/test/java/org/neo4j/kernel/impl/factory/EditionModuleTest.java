/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.factory;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.graphdb.mockfs.EphemeralFileSystemAbstraction;
import org.neo4j.kernel.api.exceptions.KernelException;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.logging.LogService;
import org.neo4j.kernel.impl.proc.Procedures;
import org.neo4j.logging.Log;

import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.neo4j.helpers.collection.MapUtil.stringMap;

public class EditionModuleTest
{
    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Test
    public void shouldFailWhenAuthEnabledAndNoAuthManagerServiceFound()
    {
        // Given
        Config config = new Config( stringMap(
                GraphDatabaseSettings.auth_manager.name(), "",
                GraphDatabaseSettings.auth_enabled.name(), "true")
        );

        LogService logService = mock( LogService.class );
        Log userLog = mock( Log.class ) ;
        when( logService.getUserLog( GraphDatabaseFacadeFactory.class ) ).thenReturn( userLog );

        // Expect
        exception.expect( IllegalArgumentException.class );
        exception.expectMessage( "Auth enabled but no auth manager found. This is an illegal product configuration." );

        // When
        new EditionModule() {
            @Override
            public void registerProcedures( Procedures procedures ) throws KernelException
            {

            }
        }.createAuthManager( config, logService, new EphemeralFileSystemAbstraction(), null );

        // Then
        verify( userLog ).error( anyString() );
    }
}
