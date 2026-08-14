/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.query;

import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.opensearch.action.search.SearchResponse;
import org.opensearch.common.settings.Settings;
import org.opensearch.index.query.MoreLikeThisQueryBuilder.Item;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.search.SearchHit;
import org.opensearch.test.ParameterizedStaticSettingsOpenSearchIntegTestCase;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import static org.opensearch.index.query.QueryBuilders.boolQuery;
import static org.opensearch.index.query.QueryBuilders.boostingQuery;
import static org.opensearch.index.query.QueryBuilders.matchAllQuery;
import static org.opensearch.index.query.QueryBuilders.moreLikeThisQuery;
import static org.opensearch.index.query.QueryBuilders.termsQuery;
import static org.opensearch.search.SearchService.CLUSTER_CONCURRENT_SEGMENT_SEARCH_SETTING;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertHitCount;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertNoFailures;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertSearchHits;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

/**
 * Proof of concept exploring the question "can I leverage {@code MUST_NOT} terms to find dissimilar
 * documents?".
 * <p>
 * The tests contrast two very different tools:
 * <ul>
 *   <li>A {@code bool} query's {@code must_not} clause runs in <b>filter context</b>: it is a hard,
 *       binary <i>exclusion</i>. Matching documents are dropped entirely and the surviving documents
 *       are all returned with the same (constant) score, so {@code must_not} on its own cannot
 *       <i>rank</i> documents by how dissimilar they are.</li>
 *   <li>A {@code boosting} query (optionally with {@code more_like_this} as its negative clause) keeps
 *       every document but <i>demotes</i> the ones that match, producing a graded "less like this"
 *       ranking that surfaces the most dissimilar documents first.</li>
 * </ul>
 * The scenarios below assert each of these behaviors on a small, deterministic corpus.
 */
public class DissimilarDocumentsIT extends ParameterizedStaticSettingsOpenSearchIntegTestCase {

    public DissimilarDocumentsIT(Settings staticSettings) {
        super(staticSettings);
    }

    @ParametersFactory
    public static Collection<Object[]> parameters() {
        return Arrays.asList(
            new Object[] { Settings.builder().put(CLUSTER_CONCURRENT_SEGMENT_SEARCH_SETTING.getKey(), false).build() },
            new Object[] { Settings.builder().put(CLUSTER_CONCURRENT_SEGMENT_SEARCH_SETTING.getKey(), true).build() }
        );
    }

    private static final String INDEX = "test";
    private static final String FIELD = "field";

    // A small corpus. Docs 0 and 1 are about the same topic (dogs); docs 2 and 4 are finance and
    // doc 3 is about a cat. The dog documents are the ones we want to treat as "similar" and exclude
    // or demote in order to surface the dissimilar (non-dog) documents.
    private static final String DOG_A = "the quick brown dog runs fast and the happy dog barks at the mailman";
    private static final String DOG_B = "a happy brown dog barks and runs quickly chasing the mailman down the street";
    private static final String FINANCE = "quarterly revenue growth exceeded market forecasts driving the stock price higher";
    private static final String CAT = "the cat sleeps on the warm windowsill all afternoon in the sun";
    private static final String BONDS = "investors weigh interest rate decisions and bond market yields carefully";

    // Distinctive terms of the "dog" documents used to describe what we consider similar.
    private static final String[] DOG_TERMS = new String[] { "dog", "barks", "mailman" };

    /**
     * Creates a single-shard index so scores are computed from global (index-wide) statistics and are
     * deterministic without needing distributed frequencies, then indexes the shared corpus.
     */
    private void setUpCorpus() {
        Settings.Builder settings = Settings.builder().put(indexSettings()).put("index.number_of_shards", 1);
        assertAcked(prepareCreate(INDEX).setSettings(settings).setMapping(FIELD, "type=text"));
        ensureGreen();

        client().prepareIndex(INDEX).setId("0").setSource(FIELD, DOG_A).get();
        client().prepareIndex(INDEX).setId("1").setSource(FIELD, DOG_B).get();
        client().prepareIndex(INDEX).setId("2").setSource(FIELD, FINANCE).get();
        client().prepareIndex(INDEX).setId("3").setSource(FIELD, CAT).get();
        client().prepareIndex(INDEX).setId("4").setSource(FIELD, BONDS).get();
        refresh();
    }

    /**
     * {@code must_not} is a hard, binary exclusion: it removes every document containing any of the
     * listed terms and returns exactly the remaining ("dissimilar") documents.
     */
    public void testMustNotHardExcludesSimilarDocuments() {
        setUpCorpus();

        QueryBuilder query = boolQuery().mustNot(termsQuery(FIELD, DOG_TERMS));
        SearchResponse response = client().prepareSearch(INDEX).setQuery(query).get();

        assertNoFailures(response);
        // Only the non-dog documents survive; the two dog documents are excluded entirely.
        assertSearchHits(response, "2", "3", "4");
    }

    /**
     * Because {@code must_not} runs in filter context it contributes no score. A query made up solely
     * of {@code must_not} clauses therefore returns every surviving document with the <b>same</b>
     * score, so it cannot rank documents by how dissimilar they are.
     */
    public void testMustNotProducesNoSimilarityRanking() {
        setUpCorpus();

        QueryBuilder query = boolQuery().mustNot(termsQuery(FIELD, DOG_TERMS));
        SearchResponse response = client().prepareSearch(INDEX).setQuery(query).get();

        assertNoFailures(response);
        assertHitCount(response, 3);

        SearchHit[] hits = response.getHits().getHits();
        float firstScore = hits[0].getScore();
        for (SearchHit hit : hits) {
            // Every surviving document shares the identical constant score - there is no gradient of
            // "how dissimilar" a document is, confirming must_not alone is exclusion, not ranking.
            assertThat(
                "must_not is a filter, so all surviving documents must share the same score",
                (double) hit.getScore(),
                closeTo(firstScore, 1e-6)
            );
        }
    }

    /**
     * A {@code boosting} query keeps every document but demotes the ones matching the "similar" terms,
     * so the dissimilar documents rise to the top while nothing is excluded. This is the graded
     * "less like this" behavior that {@code must_not} cannot provide.
     */
    public void testBoostingQueryRanksDissimilarDocumentsFirst() {
        setUpCorpus();

        QueryBuilder query = boostingQuery(matchAllQuery(), termsQuery(FIELD, DOG_TERMS)).negativeBoost(0.1f);
        SearchResponse response = client().prepareSearch(INDEX).setQuery(query).setSize(10).get();

        assertNoFailures(response);
        // Unlike must_not, boosting excludes nothing - all five documents are still returned.
        assertHitCount(response, 5);

        SearchHit[] hits = response.getHits().getHits();
        Set<String> dogIds = new HashSet<>(Arrays.asList("0", "1"));

        // The top hit is a dissimilar (non-dog) document...
        assertThat("the most dissimilar document should rank first", dogIds.contains(hits[0].getId()), is(false));
        // ...and the two demoted dog documents occupy the last two positions.
        Set<String> lastTwo = new HashSet<>(Arrays.asList(hits[hits.length - 1].getId(), hits[hits.length - 2].getId()));
        assertThat("the similar (dog) documents should be demoted to the bottom", lastTwo, is(dogIds));
        // A demoted document keeps a positive (non-zero) score - it is ranked down, not removed.
        assertThat(
            "demoted documents keep a positive score rather than being excluded",
            (double) hits[hits.length - 1].getScore(),
            greaterThan(0.0)
        );
    }

    /**
     * The negative clause of a {@code boosting} query can itself be a {@code more_like_this} query, so
     * documents are demoted by how much they resemble a seed document. The result is a ranking of the
     * corpus from most dissimilar to the seed down to the seed's nearest neighbor.
     */
    public void testBoostingWithMoreLikeThisRanksBySeedDissimilarity() {
        setUpCorpus();

        // Everything that looks like the first dog document (id 0) gets pushed down.
        QueryBuilder mlt = moreLikeThisQuery(new String[] { FIELD }, null, new Item[] { new Item(INDEX, "0") }).minTermFreq(1)
            .minDocFreq(1)
            .maxQueryTerms(25)
            // Include the seed itself so it is demoted too (by default MLT excludes the seed document).
            .include(true);
        QueryBuilder query = boostingQuery(matchAllQuery(), mlt).negativeBoost(0.1f);

        SearchResponse response = client().prepareSearch(INDEX).setQuery(query).setSize(10).get();

        assertNoFailures(response);
        assertHitCount(response, 5);

        SearchHit[] hits = response.getHits().getHits();
        // The seed and its nearest neighbor (the two dog documents) resemble the seed the most and are
        // therefore demoted the most, so the most dissimilar document ranks first.
        Set<String> dogIds = new HashSet<>(Arrays.asList("0", "1"));
        assertThat("the document least like the seed should rank first", dogIds.contains(hits[0].getId()), is(false));
        Set<String> lastTwo = new HashSet<>(Arrays.asList(hits[hits.length - 1].getId(), hits[hits.length - 2].getId()));
        assertThat("the seed and its nearest neighbor should be demoted to the bottom", lastTwo, is(dogIds));
    }
}
