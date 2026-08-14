/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.termvectors;

import org.apache.lucene.index.Fields;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.opensearch.common.settings.Settings;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;
import static org.hamcrest.Matchers.greaterThan;

/**
 * Proof of concept demonstrating that the raw statistics behind the {@code more_like_this} query
 * (term frequency and document frequency) can be pulled to the client via the Term Vectors API and
 * used to compare whether two documents are lexically similar, entirely on the client side.
 * <p>
 * {@code more_like_this} internally selects the top {@code tf * idf} terms from a seed document and
 * turns them into a boolean query; it never returns a similarity value. Here we instead request the
 * per-term statistics ({@code term_statistics}/{@code field_statistics}) for each document, rebuild a
 * tf-idf weight vector for each, and compute the cosine similarity between them. The test asserts that
 * two topically-similar documents score higher than a topically-unrelated one.
 */
public class LexicalSimilarityIT extends AbstractTermVectorsTestCase {

    public LexicalSimilarityIT(Settings staticSettings) {
        super(staticSettings);
    }

    public void testClientSideLexicalSimilarity() throws IOException {
        // Single shard so that the index-wide term/document statistics used for idf are global and
        // deterministic without needing the (more expensive) distributed frequencies (dfs) option.
        Settings.Builder settings = Settings.builder().put(indexSettings()).put("index.number_of_shards", 1);
        assertAcked(prepareCreate("test").setSettings(settings).setMapping("field", "type=text,term_vector=with_positions_offsets"));
        ensureGreen();

        // A small corpus: docs 0 and 1 are about the same topic (dogs), doc 2 is unrelated (finance).
        String docSimilarA = "the quick brown dog runs fast and the happy dog barks at the mailman";
        String docSimilarB = "a happy brown dog barks and runs quickly chasing the mailman down the street";
        String docDifferent = "quarterly revenue growth exceeded market forecasts driving the stock price higher";

        client().prepareIndex("test").setId("0").setSource("field", docSimilarA).get();
        client().prepareIndex("test").setId("1").setSource("field", docSimilarB).get();
        client().prepareIndex("test").setId("2").setSource("field", docDifferent).get();
        // A few filler docs so that idf has a meaningful corpus to work against.
        client().prepareIndex("test").setId("3").setSource("field", "the cat sleeps on the warm windowsill all afternoon").get();
        client().prepareIndex("test").setId("4").setSource("field", "investors weigh interest rate decisions and bond market yields").get();
        refresh();

        Map<String, Double> vecA = tfIdfVector("0");
        Map<String, Double> vecB = tfIdfVector("1");
        Map<String, Double> vecC = tfIdfVector("2");

        double similar = cosineSimilarity(vecA, vecB);
        double different = cosineSimilarity(vecA, vecC);

        logger.info("cosine(similar A,B)={} cosine(different A,C)={}", similar, different);

        // The two dog documents share high-value terms (dog, brown, barks, runs, happy, mailman),
        // so their tf-idf vectors must be measurably more similar than the dog-vs-finance pair.
        assertThat("similar documents must score higher than unrelated ones", similar, greaterThan(different));
        // Sanity check: the unrelated pair shares only low-value stop-like terms, so it is near zero.
        assertThat("similar documents should have a clearly positive similarity", similar, greaterThan(0.1));
    }

    /**
     * Requests the term vector for a stored document and rebuilds a client-side tf-idf weight vector
     * using only the statistics returned by the API: the in-document term frequency (tf) from the
     * postings, the per-term document frequency (docFreq) and the field's document count (docCount).
     */
    private Map<String, Double> tfIdfVector(String id) throws IOException {
        TermVectorsResponse response = client().prepareTermVectors("test", id)
            .setFieldStatistics(true)
            .setTermStatistics(true)
            .get();
        assertTrue("expected document [" + id + "] to exist", response.isExists());

        Fields fields = response.getFields();
        Terms terms = fields.terms("field");
        // docCount is the number of documents that contain this field; the idf denominator.
        long docCount = terms.getDocCount();

        Map<String, Double> vector = new HashMap<>();
        TermsEnum termsEnum = terms.iterator();
        PostingsEnum postings = null;
        while (termsEnum.next() != null) {
            String term = termsEnum.term().utf8ToString();
            // In-document term frequency (tf) for this term.
            postings = termsEnum.postings(postings, PostingsEnum.NONE);
            postings.nextDoc();
            int tf = postings.freq();
            // Index-wide document frequency (df) for this term.
            long docFreq = termsEnum.docFreq();
            // Classic Lucene idf: 1 + log(docCount / (docFreq + 1)), matching ClassicSimilarity.
            double idf = 1.0 + Math.log(docCount / (double) (docFreq + 1));
            vector.put(term, tf * idf);
        }
        return vector;
    }

    private static double cosineSimilarity(Map<String, Double> a, Map<String, Double> b) {
        Set<String> terms = new HashSet<>(a.keySet());
        terms.addAll(b.keySet());
        double dot = 0.0;
        for (String term : terms) {
            dot += a.getOrDefault(term, 0.0) * b.getOrDefault(term, 0.0);
        }
        double normA = norm(a);
        double normB = norm(b);
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (normA * normB);
    }

    private static double norm(Map<String, Double> vector) {
        double sum = 0.0;
        for (double weight : vector.values()) {
            sum += weight * weight;
        }
        return Math.sqrt(sum);
    }
}
