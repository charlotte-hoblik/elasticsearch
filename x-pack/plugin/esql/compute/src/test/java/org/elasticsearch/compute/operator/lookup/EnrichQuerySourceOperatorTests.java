/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.lookup;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.tests.store.MockDirectoryWrapper;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.MockBigArrays;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BlockUtils;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.DocBlock;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.Warnings;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.index.mapper.KeywordFieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.test.ESTestCase;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.mockito.Mockito.mock;

public class EnrichQuerySourceOperatorTests extends ESTestCase {

    private BlockFactory blockFactory;

    @Before
    public void setupBlockFactory() {
        BigArrays bigArrays = new MockBigArrays(PageCacheRecycler.NON_RECYCLING_INSTANCE, ByteSizeValue.ofGb(1)).withCircuitBreaking();
        CircuitBreaker breaker = bigArrays.breakerService().getBreaker(CircuitBreaker.REQUEST);
        this.blockFactory = new BlockFactory(breaker, bigArrays);
    }

    @After
    public void allBreakersEmpty() throws Exception {
        MockBigArrays.ensureAllArraysAreReleased();
        assertThat(blockFactory.breaker().getUsed(), equalTo(0L));
    }

    public void testQueries() throws Exception {
        try (
            var directoryData = makeDirectoryWith(
                List.of(List.of("a2"), List.of("a1", "c1", "b2"), List.of("a2"), List.of("a3"), List.of("b2", "b1", "a1"))
            )
        ) {
            final BytesRefBlock inputTerms;
            try (BytesRefBlock.Builder termBuilder = blockFactory.newBytesRefBlockBuilder(6)) {
                termBuilder.appendBytesRef(new BytesRef("b2"))
                    .beginPositionEntry()
                    .appendBytesRef(new BytesRef("c1"))
                    .appendBytesRef(new BytesRef("a2"))
                    .endPositionEntry()
                    .appendBytesRef(new BytesRef("z2"))
                    .appendNull()
                    .appendBytesRef(new BytesRef("a3"))
                    .appendNull();
                inputTerms = termBuilder.build();
            }
            MappedFieldType uidField = new KeywordFieldMapper.KeywordFieldType("uid");
            QueryList queryList = QueryList.rawTermQueryList(uidField, mock(SearchExecutionContext.class), inputTerms);
            assertThat(queryList.getPositionCount(), equalTo(6));
            assertThat(queryList.getQuery(0), equalTo(new TermQuery(new Term("uid", new BytesRef("b2")))));
            assertThat(queryList.getQuery(1), equalTo(new TermInSetQuery("uid", List.of(new BytesRef("c1"), new BytesRef("a2")))));
            assertThat(queryList.getQuery(2), equalTo(new TermQuery(new Term("uid", new BytesRef("z2")))));
            assertNull(queryList.getQuery(3));
            assertThat(queryList.getQuery(4), equalTo(new TermQuery(new Term("uid", new BytesRef("a3")))));
            assertNull(queryList.getQuery(5));
            // pos -> terms -> docs
            // -----------------------------
            // 0 -> [b2] -> [1, 4]
            // 1 -> [c1, a2] -> [1, 0, 2]
            // 2 -> [z2] -> []
            // 3 -> [] -> []
            // 4 -> [a1] -> [3]
            // 5 -> [] -> []
            var warnings = Warnings.createWarnings(DriverContext.WarningsMode.IGNORE, 0, 0, "test enrich");
            EnrichQuerySourceOperator queryOperator = new EnrichQuerySourceOperator(
                blockFactory,
                128,
                queryList,
                directoryData.reader,
                warnings
            );
            Page page = queryOperator.getOutput();
            assertNotNull(page);
            assertThat(page.getPositionCount(), equalTo(6));
            IntVector docs = getDocVector(page, 0);
            assertThat(docs.getInt(0), equalTo(1));
            assertThat(docs.getInt(1), equalTo(4));
            assertThat(docs.getInt(2), equalTo(0));
            assertThat(docs.getInt(3), equalTo(1));
            assertThat(docs.getInt(4), equalTo(2));
            assertThat(docs.getInt(5), equalTo(3));

            Block positions = page.getBlock(1);
            assertThat(BlockUtils.toJavaObject(positions, 0), equalTo(0));
            assertThat(BlockUtils.toJavaObject(positions, 1), equalTo(0));
            assertThat(BlockUtils.toJavaObject(positions, 2), equalTo(1));
            assertThat(BlockUtils.toJavaObject(positions, 3), equalTo(1));
            assertThat(BlockUtils.toJavaObject(positions, 4), equalTo(1));
            assertThat(BlockUtils.toJavaObject(positions, 5), equalTo(4));
            page.releaseBlocks();
            assertTrue(queryOperator.isFinished());
            IOUtils.close(inputTerms);
        }
    }

    public void testRandomMatchQueries() throws Exception {
        int numTerms = randomIntBetween(10, 1000);
        List<List<String>> termsList = IntStream.range(0, numTerms).mapToObj(i -> List.of("term-" + i)).toList();
        Map<String, Integer> terms = IntStream.range(0, numTerms).boxed().collect(Collectors.toMap(i -> "term-" + i, i -> i));

        try (var directoryData = makeDirectoryWith(termsList)) {
            Map<Integer, Set<Integer>> expectedPositions = new HashMap<>();
            int numPositions = randomIntBetween(1, 1000);
            final BytesRefBlock inputTerms;
            try (BytesRefBlock.Builder builder = blockFactory.newBytesRefBlockBuilder(numPositions)) {
                for (int i = 0; i < numPositions; i++) {
                    if (randomBoolean()) {
                        String term = randomFrom(terms.keySet());
                        builder.appendBytesRef(new BytesRef(term));
                        Integer position = terms.get(term);
                        expectedPositions.put(i, Set.of(position));
                    } else {
                        if (randomBoolean()) {
                            builder.appendNull();
                        } else {
                            String term = "other-" + randomIntBetween(1, 100);
                            builder.appendBytesRef(new BytesRef(term));
                        }
                    }
                }
                inputTerms = builder.build();
            }
            var queryList = QueryList.rawTermQueryList(directoryData.field, mock(SearchExecutionContext.class), inputTerms);
            int maxPageSize = between(1, 256);
            var warnings = Warnings.createWarnings(DriverContext.WarningsMode.IGNORE, 0, 0, "test enrich");
            EnrichQuerySourceOperator queryOperator = new EnrichQuerySourceOperator(
                blockFactory,
                maxPageSize,
                queryList,
                directoryData.reader,
                warnings
            );
            Map<Integer, Set<Integer>> actualPositions = new HashMap<>();
            while (queryOperator.isFinished() == false) {
                Page page = queryOperator.getOutput();
                if (page != null) {
                    IntVector docs = getDocVector(page, 0);
                    IntBlock positions = page.getBlock(1);
                    assertThat(positions.getPositionCount(), lessThanOrEqualTo(maxPageSize));
                    for (int i = 0; i < page.getPositionCount(); i++) {
                        int doc = docs.getInt(i);
                        int position = positions.getInt(i);
                        actualPositions.computeIfAbsent(position, k -> new HashSet<>()).add(doc);
                    }
                    page.releaseBlocks();
                }
            }
            assertThat(actualPositions, equalTo(expectedPositions));
            IOUtils.close(inputTerms);
        }
    }

    public void testQueries_OnlySingleValues() throws Exception {
        try (
            var directoryData = makeDirectoryWith(
                List.of(List.of("a2"), List.of("a1", "c1", "b2"), List.of("a2"), List.of("a3"), List.of("b2", "b1", "a1"))
            )
        ) {
            final BytesRefBlock inputTerms;
            try (BytesRefBlock.Builder termBuilder = blockFactory.newBytesRefBlockBuilder(6)) {
                termBuilder.appendBytesRef(new BytesRef("b2"))
                    .beginPositionEntry()
                    .appendBytesRef(new BytesRef("c1"))
                    .appendBytesRef(new BytesRef("a2"))
                    .endPositionEntry()
                    .appendBytesRef(new BytesRef("z2"))
                    .appendNull()
                    .appendBytesRef(new BytesRef("a3"))
                    .beginPositionEntry()
                    .appendBytesRef(new BytesRef("a3"))
                    .appendBytesRef(new BytesRef("a2"))
                    .appendBytesRef(new BytesRef("z2"))
                    .appendBytesRef(new BytesRef("xx"))
                    .endPositionEntry();
                inputTerms = termBuilder.build();
            }
            QueryList queryList = QueryList.rawTermQueryList(directoryData.field, mock(SearchExecutionContext.class), inputTerms)
                .onlySingleValues();
            // pos -> terms -> docs
            // -----------------------------
            // 0 -> [b2] -> [1, 4]
            // 1 -> [c1, a2] -> []
            // 2 -> [z2] -> []
            // 3 -> [] -> []
            // 4 -> [a1] -> [3]
            // 5 -> [a3, a2, z2, xx] -> []
            var warnings = Warnings.createWarnings(DriverContext.WarningsMode.IGNORE, 0, 0, "test lookup");
            EnrichQuerySourceOperator queryOperator = new EnrichQuerySourceOperator(
                blockFactory,
                128,
                queryList,
                directoryData.reader,
                warnings
            );
            Page page = queryOperator.getOutput();
            assertNotNull(page);
            assertThat(page.getPositionCount(), equalTo(3));
            IntVector docs = getDocVector(page, 0);
            assertThat(docs.getInt(0), equalTo(1));
            assertThat(docs.getInt(1), equalTo(4));
            assertThat(docs.getInt(2), equalTo(3));

            Block positions = page.getBlock(1);
            assertThat(BlockUtils.toJavaObject(positions, 0), equalTo(0));
            assertThat(BlockUtils.toJavaObject(positions, 1), equalTo(0));
            assertThat(BlockUtils.toJavaObject(positions, 2), equalTo(4));
            page.releaseBlocks();
            assertTrue(queryOperator.isFinished());
            IOUtils.close(inputTerms);
        }
    }

    private static IntVector getDocVector(Page page, int blockIndex) {
        DocBlock doc = page.getBlock(blockIndex);
        return doc.asVector().docs();
    }

    private record DirectoryData(DirectoryReader reader, MockDirectoryWrapper dir, MappedFieldType field) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            IOUtils.close(reader, dir);
        }
    }

    private static DirectoryData makeDirectoryWith(List<List<String>> terms) throws IOException {
        MockDirectoryWrapper dir = newMockDirectory();
        IndexWriterConfig iwc = new IndexWriterConfig();
        iwc.setMergePolicy(NoMergePolicy.INSTANCE);
        try (IndexWriter writer = new IndexWriter(dir, iwc)) {
            for (var termList : terms) {
                Document doc = new Document();
                for (String term : termList) {
                    doc.add(new StringField("uid", term, Field.Store.NO));
                }
                writer.addDocument(doc);
            }
            writer.forceMerge(1);
            writer.commit();

            return new DirectoryData(DirectoryReader.open(writer), dir, new KeywordFieldMapper.KeywordFieldType("uid"));
        }
    }
}
