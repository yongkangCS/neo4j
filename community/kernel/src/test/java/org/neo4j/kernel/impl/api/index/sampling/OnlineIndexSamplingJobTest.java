/**
 * Copyright (c) 2002-2014 "Neo Technology,"
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
package org.neo4j.kernel.impl.api.index.sampling;

import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import org.neo4j.kernel.api.exceptions.index.IndexNotFoundKernelException;
import org.neo4j.kernel.api.index.IndexConfiguration;
import org.neo4j.kernel.api.index.IndexDescriptor;
import org.neo4j.kernel.api.index.IndexReader;
import org.neo4j.kernel.api.index.ValueSampler;
import org.neo4j.kernel.impl.api.index.IndexProxy;
import org.neo4j.kernel.impl.api.index.IndexStoreView;
import org.neo4j.kernel.logging.DevNullLoggingService;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import static org.neo4j.kernel.api.index.InternalIndexState.FAILED;
import static org.neo4j.kernel.api.index.InternalIndexState.ONLINE;

public class OnlineIndexSamplingJobTest
{
    @Test
    public void shouldSampleTheIndexAndStoreTheValueWhenTheIndexIsOnline()
    {
        // given
        OnlineIndexSamplingJob job = new OnlineIndexSamplingJob( indexProxy, numOfUniqueElements, indexStoreView, logging );
        when( indexProxy.getState() ).thenReturn( ONLINE );

        // when
        job.run();

        // then
        verify( indexStoreView ).replaceIndexCounts( indexDescriptor, indexUniqueValues, indexSize, indexSize );
        verifyNoMoreInteractions( indexStoreView );
    }

    @Test
    public void shouldSampleTheIndexButDoNotStoreTheValuesIfTheIndexIsNotOnline()
    {
        // given
        OnlineIndexSamplingJob job = new OnlineIndexSamplingJob( indexProxy, numOfUniqueElements, indexStoreView, logging );
        when( indexProxy.getState() ).thenReturn( FAILED );

        // when
        job.run();

        // then
        verifyNoMoreInteractions( indexStoreView );
    }

    private final DevNullLoggingService logging = new DevNullLoggingService();
    private final IndexProxy indexProxy = mock( IndexProxy.class );
    private final IndexStoreView indexStoreView = mock( IndexStoreView.class );
    private final IndexDescriptor indexDescriptor = new IndexDescriptor( 1, 2 );
    private final IndexReader indexReader = mock( IndexReader.class );

    private final long indexUniqueValues = 21l;
    private final long indexSize = 23l;
    private final int numOfUniqueElements = 42;

    @Before
    public void setup() throws IndexNotFoundKernelException
    {
        when( indexProxy.getDescriptor() ).thenReturn( indexDescriptor );
        when( indexProxy.config() ).thenReturn( new IndexConfiguration( false ) );
        when( indexProxy.newReader() ).thenReturn( indexReader );
        doAnswer( answerWith( indexUniqueValues, indexSize ) ).when( indexReader )
                .sampleIndex( any( ValueSampler.class ) );

    }

    private Answer<Void> answerWith( final long indexUniqueValues, final long indexSize )
    {
        return new Answer<Void>()
        {
            @Override
            public Void answer( InvocationOnMock invocationOnMock ) throws Throwable
            {
                final ValueSampler sampler = (ValueSampler) invocationOnMock.getArguments()[0];
                for ( long i = indexUniqueValues - 1; i > 0 ; i--)
                {
                    sampler.include( "" + i );
                }
                for ( long i = (indexSize - indexUniqueValues) + 1; i > 0 ; i--)
                {
                    sampler.include( "same value" );
                }
                return null;
            }
        };
    }
}
