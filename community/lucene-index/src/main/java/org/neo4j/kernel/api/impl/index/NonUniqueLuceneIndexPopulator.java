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
package org.neo4j.kernel.api.impl.index;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.lucene.document.Fieldable;

import org.neo4j.kernel.api.index.IndexEntryConflictException;
import org.neo4j.kernel.api.index.IndexUpdater;
import org.neo4j.kernel.api.index.NodePropertyUpdate;
import org.neo4j.kernel.api.index.PropertyAccessor;
import org.neo4j.kernel.api.index.ValueSampler;
import org.neo4j.kernel.api.index.util.FailureStorage;

class NonUniqueLuceneIndexPopulator extends LuceneIndexPopulator
{
    static final int DEFAULT_QUEUE_THRESHOLD = 10000;
    private final int queueThreshold;
    private final ValueSampler sampler;
    private final List<NodePropertyUpdate> updates = new ArrayList<>();

    NonUniqueLuceneIndexPopulator( int queueThreshold, LuceneDocumentStructure documentStructure,
                                   LuceneIndexWriterFactory indexWriterFactory,
                                   IndexWriterStatus writerStatus, DirectoryFactory dirFactory, File dirFile,
                                   FailureStorage failureStorage, long indexId, ValueSampler sampler )
    {
        super( documentStructure, indexWriterFactory, writerStatus, dirFactory, dirFile, failureStorage, indexId );
        this.queueThreshold = queueThreshold;
        this.sampler = sampler;
    }

    @Override
    public void add( long nodeId, Object propertyValue ) throws IOException
    {
        Fieldable encodedValue = documentStructure.encodeAsFieldable( propertyValue );
        sampler.include( encodedValue.stringValue() );
        writer.addDocument( documentStructure.newDocumentRepresentingProperty( nodeId, encodedValue ) );
    }

    @Override
    public void verifyDeferredConstraints( PropertyAccessor accessor ) throws IndexEntryConflictException, IOException
    {
        // no constraints to verify so do nothing
    }

    @Override
    public IndexUpdater newPopulatingUpdater( PropertyAccessor propertyAccessor ) throws IOException
    {
        return new IndexUpdater()
        {
            @Override
            public void process( NodePropertyUpdate update ) throws IOException, IndexEntryConflictException
            {
                switch ( update.getUpdateMode() )
                {
                    case ADDED:
                        // We don't look at the "before" value, so adding and changing idempotently is done the same way.
                        Fieldable encodedValue = documentStructure.encodeAsFieldable( update.getValueAfter() );
                        sampler.include( encodedValue.stringValue() );
                        break;
                    case CHANGED:
                        // We don't look at the "before" value, so adding and changing idempotently is done the same way.
                        Fieldable encodedValueBefore = documentStructure.encodeAsFieldable( update.getValueBefore() );
                        sampler.exclude( encodedValueBefore.stringValue() );
                        Fieldable encodedValueAfter = documentStructure.encodeAsFieldable( update.getValueAfter() );
                        sampler.include( encodedValueAfter.stringValue() );
                        break;
                    case REMOVED:
                        Fieldable removedValue = documentStructure.encodeAsFieldable( update.getValueBefore() );
                        sampler.exclude( removedValue.stringValue() );
                        break;
                    default:
                        throw new IllegalStateException( "Unknown update mode " + update.getUpdateMode() );
                }

                updates.add( update );
            }

            @Override
            public void close() throws IOException, IndexEntryConflictException
            {
                if ( updates.size() > queueThreshold )
                {
                    flush();
                    updates.clear();
                }

            }

            @Override
            public void remove( Collection<Long> nodeIds ) throws IOException
            {
                throw new UnsupportedOperationException( "Should not remove() from populating index." );
            }
        };
    }

    @Override
    protected void flush() throws IOException
    {
        for ( NodePropertyUpdate update : this.updates )
        {
            long nodeId = update.getNodeId();
            switch ( update.getUpdateMode() )
            {
            case ADDED:
            case CHANGED:
                // We don't look at the "before" value, so adding and changing idempotently is done the same way.
                Fieldable encodedValue = documentStructure.encodeAsFieldable( update.getValueAfter() );
                writer.updateDocument( documentStructure.newQueryForChangeOrRemove( nodeId ),
                                       documentStructure.newDocumentRepresentingProperty( nodeId, encodedValue ) );
                break;
            case REMOVED:
                writer.deleteDocuments( documentStructure.newQueryForChangeOrRemove( nodeId ) );
                break;
            default:
                throw new IllegalStateException( "Unknown update mode " + update.getUpdateMode() );
            }
        }
    }
}
