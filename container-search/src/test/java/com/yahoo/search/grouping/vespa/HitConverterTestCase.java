// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.search.grouping.vespa;

import com.yahoo.document.DocumentId;
import com.yahoo.document.GlobalId;
import com.yahoo.net.URI;
import com.yahoo.prelude.fastsearch.DocumentDatabase;
import com.yahoo.prelude.fastsearch.GroupingListHit;
import com.yahoo.prelude.fastsearch.FastHit;
import com.yahoo.search.Query;
import com.yahoo.search.Result;
import com.yahoo.search.Searcher;
import com.yahoo.search.result.Hit;
import com.yahoo.search.result.Relevance;
import com.yahoo.search.schema.DocumentSummary;
import com.yahoo.search.schema.Schema;
import com.yahoo.search.searchchain.Execution;
import com.yahoo.searchlib.aggregation.FS4Hit;
import com.yahoo.searchlib.aggregation.VdsHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Simon Thoresen Hult
 */
public class HitConverterTestCase {

    private GlobalId createGlobalId(int docId) {
        return new GlobalId((new DocumentId("id:ns:type::" + docId)).getGlobalId());
    }

    @Test
    void requireThatHitsAreConverted() {
        Query query = new Query();
        HitConverter converter = new HitConverter(new MySearcher());
        Hit hit = converter.toSearchHit("default", new FS4Hit(1, createGlobalId(2), 3).setContext(context(query)));
        assertNotNull(hit);
        assertEquals(new URI("index:null/1/" + asHexString(createGlobalId(2))), hit.getId());

        hit = converter.toSearchHit("default", new FS4Hit(4, createGlobalId(5), 6).setContext(context(query)));
        assertNotNull(hit);
        assertEquals(new URI("index:null/4/" + asHexString(createGlobalId(5))), hit.getId());
    }

    @Test
    void requireThatContextDataIsCopied() {
        Query ctxQuery = new Query();
        Hit ctxHit = context(ctxQuery);
        ctxHit.setSource("69");

        HitConverter converter = new HitConverter(new MySearcher());
        Hit hit = converter.toSearchHit("default", new FS4Hit(1, createGlobalId(2), 3).setContext(ctxHit));
        assertNotNull(hit);
        assertTrue(hit instanceof FastHit);
        assertEquals(1, ((FastHit) hit).getPartId());
        assertEquals(createGlobalId(2), ((FastHit) hit).getGlobalId());
        assertSame(ctxQuery, hit.getQuery());
        assertEquals(ctxHit.getSource(), hit.getSource());
    }

    @Test
    void requireThatSummaryClassIsSet() {
        Query query = new Query();
        Searcher searcher = new MySearcher();
        HitConverter converter = new HitConverter(searcher);
        Hit hit = converter.toSearchHit("69", new FS4Hit(1, createGlobalId(2), 3).setContext(context(query)));
        assertNotNull(hit);
        assertTrue(hit instanceof FastHit);
        assertEquals("69", hit.getSearcherSpecificMetaData(searcher));
    }

    @Test
    void requireThatHitHasContext() {
        HitConverter converter = new HitConverter(new MySearcher());
        try {
            converter.toSearchHit("69", new FS4Hit(1, createGlobalId(2), 3));
            fail();
        } catch (NullPointerException e) {

        }
    }

    @Test
    void requireThatUnsupportedHitClassThrows() {
        HitConverter converter = new HitConverter(new MySearcher());
        try {
            converter.toSearchHit("69", new com.yahoo.searchlib.aggregation.Hit() {

            });
            fail();
        } catch (UnsupportedOperationException e) {

        }
    }

    private static GroupingListHit context(Query query) {
        return new GroupingListHit(List.of(), null, query);
    }

    private static DocumentDatabase sixtynine() {
        var schema = new Schema.Builder("none");
        var summary = new DocumentSummary.Builder("69");
        schema.add(summary.build());
        return new DocumentDatabase(schema.build());
    }

    @Test
    void requireThatVdsHitCanBeConverted() {
        Query query = new Query();
        HitConverter converter = new HitConverter(new MySearcher());
        GroupingListHit context = new GroupingListHit(null, sixtynine(), query);
        VdsHit lowHit = new VdsHit("id:ns:type::", new byte[]{0x55, 0x55, 0x55, 0x55}, 1);
        lowHit.setContext(context);
        Hit hit = converter.toSearchHit("69", lowHit);
        assertNotNull(hit);
        assertTrue(hit instanceof FastHit);
        assertEquals(new Relevance(1), hit.getRelevance());
        assertTrue(hit.isFilled("69"));
        assertEquals("none", hit.getField(Hit.SDDOCNAME_FIELD));
        assertSame(query, hit.getQuery());
    }

    private static String asHexString(GlobalId gid) {
        StringBuilder sb = new StringBuilder();
        byte[] rawGid = gid.getRawId();
        for (byte b : rawGid) {
            String hex = Integer.toHexString(0xFF & b);
            if (hex.length() == 1)
                sb.append('0');
            sb.append(hex);
        }
        return sb.toString();
    }

    private static class MySearcher extends Searcher {

        @Override
        public Result search(Query query, Execution exec) {
            return exec.search(query);
        }
    }

}
