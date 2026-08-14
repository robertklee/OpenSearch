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
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.opensearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;

/**
 * Proof of concept demonstrating that the raw statistics behind the {@code more_like_this} query
 * (term frequency and document frequency) can be pulled to the client via the Term Vectors API and
 * used to compare whether two or more documents are lexically similar, entirely on the client side.
 * <p>
 * {@code more_like_this} internally selects the top {@code tf * idf} terms from a seed document and
 * turns them into a boolean query; it never returns a similarity value. Here we instead request the
 * per-term statistics ({@code term_statistics}/{@code field_statistics}) for each document, rebuild a
 * tf-idf weight vector for each, and compute the cosine similarity between them.
 * <p>
 * The scenarios below exercise:
 * <ul>
 *   <li>{@link #testSimilarDocumentsScoreHigherThanUnrelated()} - a similar pair outscores an unrelated pair.</li>
 *   <li>{@link #testSelfSimilarityIsMaximal()} - a document compared with itself scores 1.0 and dominates all others.</li>
 *   <li>{@link #testRankingBySimilarity()} - ranking a set of documents against a query document by cosine similarity.</li>
 *   <li>{@link #testArtificialDocumentSimilarity()} - comparing an un-indexed (artificial) document against the corpus.</li>
 * </ul>
 * All tf-idf weights are rebuilt on the client purely from the {@code term_freq}, {@code doc_freq} and
 * {@code doc_count} values returned by the Term Vectors API - the same inputs {@code more_like_this} uses.
 */
public class LexicalSimilarityIT extends AbstractTermVectorsTestCase {

    public LexicalSimilarityIT(Settings staticSettings) {
        super(staticSettings);
    }

    private static final String INDEX = "test";
    private static final String FIELD = "field";

    // A small corpus. Docs 0 and 1 are about the same topic (dogs); doc 2 is unrelated (finance).
    // Docs 3 and 4 are additional filler so that idf has a meaningful corpus to work against.
    private static final String DOG_A = "the quick brown dog runs fast and the happy dog barks at the mailman";
    private static final String DOG_B = "a happy brown dog barks and runs quickly chasing the mailman down the street";
    private static final String FINANCE = "quarterly revenue growth exceeded market forecasts driving the stock price higher";
    private static final String CAT = "the cat sleeps on the warm windowsill all afternoon in the sun";
    private static final String BONDS = "investors weigh interest rate decisions and bond market yields carefully";

    /**
     * Creates a single-shard index so that the index-wide term/document statistics used for idf are
     * global and deterministic without needing the (more expensive) distributed frequencies (dfs)
     * option, then indexes the shared corpus.
     */
    private void setUpCorpus() {
        Settings.Builder settings = Settings.builder().put(indexSettings()).put("index.number_of_shards", 1);
        assertAcked(prepareCreate(INDEX).setSettings(settings).setMapping(FIELD, "type=text,term_vector=with_positions_offsets"));
        ensureGreen();

        client().prepareIndex(INDEX).setId("0").setSource(FIELD, DOG_A).get();
        client().prepareIndex(INDEX).setId("1").setSource(FIELD, DOG_B).get();
        client().prepareIndex(INDEX).setId("2").setSource(FIELD, FINANCE).get();
        client().prepareIndex(INDEX).setId("3").setSource(FIELD, CAT).get();
        client().prepareIndex(INDEX).setId("4").setSource(FIELD, BONDS).get();
        refresh();
    }

    public void testSimilarDocumentsScoreHigherThanUnrelated() throws IOException {
        setUpCorpus();

        Map<String, Double> dogA = tfIdfVector(termVectorForDoc("0"));
        Map<String, Double> dogB = tfIdfVector(termVectorForDoc("1"));
        Map<String, Double> finance = tfIdfVector(termVectorForDoc("2"));

        double similar = cosineSimilarity(dogA, dogB);
        double unrelated = cosineSimilarity(dogA, finance);

        logger.info("cosine(dogA,dogB)={} cosine(dogA,finance)={}", similar, unrelated);

        // The two dog documents share high-value terms (dog, brown, barks, runs, happy, mailman),
        // so their tf-idf vectors must be measurably more similar than the dog-vs-finance pair.
        assertThat("similar documents must score higher than unrelated ones", similar, greaterThan(unrelated));
        assertThat("similar documents should have a clearly positive similarity", similar, greaterThan(0.1));
        assertThat("unrelated documents should be near zero", unrelated, lessThan(0.1));
    }

    public void testSelfSimilarityIsMaximal() throws IOException {
        setUpCorpus();

        Map<String, Double> dogA = tfIdfVector(termVectorForDoc("0"));
        Map<String, Double> dogASecondFetch = tfIdfVector(termVectorForDoc("0"));
        Map<String, Double> dogB = tfIdfVector(termVectorForDoc("1"));
        Map<String, Double> finance = tfIdfVector(termVectorForDoc("2"));

        double self = cosineSimilarity(dogA, dogASecondFetch);
        // Cosine similarity of a vector with itself is exactly 1.0 (bounded floating point error).
        assertThat("a document compared with itself must have cosine similarity 1.0", self, closeTo(1.0, 1e-9));
        // Self-similarity must dominate similarity with any other document.
        assertThat(self, greaterThan(cosineSimilarity(dogA, dogB)));
        assertThat(self, greaterThan(cosineSimilarity(dogA, finance)));
    }

    public void testRankingBySimilarity() throws IOException {
        setUpCorpus();

        // Use the first dog document as the "query" and rank every other document against it.
        Map<String, Double> query = tfIdfVector(termVectorForDoc("0"));

        String[] candidateIds = new String[] { "1", "2", "3", "4" };
        String bestId = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (String id : candidateIds) {
            double score = cosineSimilarity(query, tfIdfVector(termVectorForDoc(id)));
            logger.info("cosine(query, doc {})={}", id, score);
            if (score > bestScore) {
                bestScore = score;
                bestId = id;
            }
        }

        // Document 1 (the other dog document) is the only topically-related candidate and must rank first.
        assertEquals("the other dog document should be the most similar candidate", "1", bestId);
        assertThat(bestScore, greaterThan(0.1));
    }

    public void testArtificialDocumentSimilarity() throws IOException {
        setUpCorpus();

        // An artificial (un-indexed) document is analyzed on the fly and its term statistics are
        // resolved against the existing corpus - exactly what more_like_this does for `like` text.
        String artificialDogText = "the friendly brown dog happily barks and runs after the mailman";
        Map<String, Double> artificialDog = tfIdfVector(termVectorForArtificialDoc(artificialDogText, "0"));

        Map<String, Double> dogA = tfIdfVector(termVectorForDoc("0"));
        Map<String, Double> finance = tfIdfVector(termVectorForDoc("2"));

        double toDog = cosineSimilarity(artificialDog, dogA);
        double toFinance = cosineSimilarity(artificialDog, finance);

        logger.info("cosine(artificial, dogA)={} cosine(artificial, finance)={}", toDog, toFinance);

        assertThat("an un-indexed dog document must be more similar to the dog document", toDog, greaterThan(toFinance));
        assertThat(toDog, greaterThan(0.1));
    }

    /** Requests the term vector (with statistics) for a stored document. */
    private TermVectorsResponse termVectorForDoc(String id) {
        return client().prepareTermVectors(INDEX, id).setFieldStatistics(true).setTermStatistics(true).get();
    }

    /**
     * Requests the term vector (with statistics) for an artificial document that is not indexed.
     * Routing is pinned so the statistics are resolved on the same shard as the reference document.
     */
    private TermVectorsResponse termVectorForArtificialDoc(String text, String routing) throws IOException {
        XContentBuilder doc = jsonBuilder().startObject().field(FIELD, text).endObject();
        return client().prepareTermVectors()
            .setIndex(INDEX)
            .setRouting(routing)
            .setDoc(doc)
            .setFieldStatistics(true)
            .setTermStatistics(true)
            .get();
    }

    /**
     * Rebuilds a client-side tf-idf weight vector using only the statistics returned by the API: the
     * in-document term frequency (tf) from the postings, the per-term document frequency (docFreq) and
     * the field's document count (docCount).
     */
    private Map<String, Double> tfIdfVector(TermVectorsResponse response) throws IOException {
        assertTrue("expected the term vector response to exist", response.isExists());

        Fields fields = response.getFields();
        Terms terms = fields.terms(FIELD);
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
