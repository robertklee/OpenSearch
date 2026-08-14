# Proof of Concept: Client-Side Lexical Document Similarity via the Term Vectors API

## Summary

OpenSearch's `more_like_this` (MLT) query is built on classic **tf-idf** term
weighting. MLT itself does not return a similarity score between two documents —
it selects the highest-weighted terms from a seed input and turns them into a
Boolean query. This report shows that the *same statistics* MLT relies on
(`term_freq`, `doc_freq`, `doc_count`) are directly retrievable through the
**Term Vectors API** (`_termvectors` / `_mtermvectors`), and that a client can
use them to compute a concrete similarity value (e.g. cosine similarity) between
two or more documents — entirely on the client side.

A proof-of-concept internal cluster test,
[`LexicalSimilarityIT`](../server/src/internalClusterTest/java/org/opensearch/action/termvectors/LexicalSimilarityIT.java),
demonstrates and asserts this behavior.

## How `more_like_this` works

Relevant source:

- `server/src/main/java/org/opensearch/index/query/MoreLikeThisQueryBuilder.java`
- `server/src/main/java/org/opensearch/common/lucene/search/MoreLikeThisQuery.java`
- `server/src/main/java/org/opensearch/common/lucene/search/XMoreLikeThis.java`

The pipeline:

1. **Gather term frequencies from the seed(s).** The `like` input can be free
   text or a reference to a document (`{_index, _id}` or an artificial `doc`).
   For document references, the builder fetches **term vectors** via a
   `MultiTermVectorsRequest`; for raw text it analyzes the text with the field
   analyzer. Either way it produces a `term → tf` map.
2. **Score each candidate term by tf·idf.** In `XMoreLikeThis.createQueue`
   (≈ lines 736–784):
   - `tf` = term frequency in the seed document,
   - `docFreq = ir.docFreq(term)` = number of documents in the index containing
     the term,
   - `idf = similarity.idf(docFreq, numDocs)` using `ClassicSimilarity` (a
     `TFIDFSimilarity`),
   - `score = tf * idf`.
3. **Filter terms** by tunable knobs: `min_term_freq` (default 2),
   `min_doc_freq` (5), `max_doc_freq`, `min/max_word_length`,
   `max_query_terms` (25), `stop_words`.
4. **Build a Boolean query.** The top-N terms become `SHOULD` clauses
   (optionally boosted by tf·idf), `unlike` terms become `MUST_NOT`, combined
   with `minimum_should_match` (default `"30%"`).

The tf·idf computation is therefore internal and collapsed into a query; the
per-term statistics are never returned to the caller.

## Retrieving the statistics with the Term Vectors API

Relevant source:

- `server/src/main/java/org/opensearch/action/termvectors/TermVectorsRequest.java`
- `server/src/main/java/org/opensearch/action/termvectors/TermVectorsResponse.java`

The Term Vectors API is the same data source MLT uses, and it exposes the raw
values through request flags:

| Flag | Returns | idf role |
| --- | --- | --- |
| (always) `term_freq` | in-document term frequency (**tf**) | numerator |
| `term_statistics=true` | per-term `doc_freq` and `ttf` | idf numerator/denominator |
| `field_statistics=true` (default on) | `sum_doc_freq`, `doc_count`, `sum_ttf` | corpus-level denominators |
| `dfs=true` | statistics aggregated across shards | makes idf index-global |

Additional capabilities that make this suitable for document comparison:

- **Artificial documents.** A `doc` body can be supplied in the request and is
  analyzed on the fly (never indexed), so brand-new documents can be scored
  against the existing corpus — exactly what MLT does for `like` text.
- **Batch retrieval.** `_mtermvectors` fetches term vectors for multiple
  documents (or multiple artificial docs) in a single request.
- **Normalized token space.** Returned terms are post-analysis tokens
  (lowercased, stemmed, stop-filtered depending on the analyzer), so two
  documents are always compared in the same normalized space.

## Client-side similarity recipe

1. For each document, call `_termvectors` (or `_mtermvectors`) with
   `term_statistics=true`, `field_statistics=true`, and — for multi-shard
   indices — `dfs=true`. Use artificial `doc` bodies for un-indexed content.
2. Build a tf-idf weight vector per document:
   - `tf` from `term_freq`,
   - `idf ≈ 1 + log(doc_count / (doc_freq + 1))`, matching Lucene's
     `ClassicSimilarity.idf`.
3. Compare the vectors with **cosine similarity** (or Jaccard on the term sets
   for pure overlap).

### Caveats

- Statistics are **shard-local** unless `dfs=true` is passed; for a consistent
  corpus idf use `dfs=true` or a single shard.
- The field must be indexed; storing term vectors in the mapping
  (`"term_vector": "with_positions_offsets"`) makes retrieval cheaper.
- MLT's thresholds (`min_term_freq`, `min_doc_freq`, `max_query_terms`) are
  query-building heuristics. On the client you hold the raw `tf` / `doc_freq` /
  `ttf` values and can apply your own weighting and filtering.

## Proof-of-concept test

The test builds a five-document corpus in a single-shard index (so idf is
global and deterministic without `dfs`):

| id | topic | text (abbreviated) |
| --- | --- | --- |
| 0 | dogs | "the quick brown **dog** runs fast … **barks** at the **mailman**" |
| 1 | dogs | "a happy brown **dog** **barks** and runs quickly … the **mailman**" |
| 2 | finance | "quarterly revenue growth … **stock price** higher" |
| 3 | cats | "the **cat** sleeps on the warm **windowsill** …" |
| 4 | finance | "investors weigh **interest rate** decisions and **bond** market **yields**" |

For each document the test requests the term vector with `term_statistics` and
`field_statistics`, rebuilds the tf-idf vector on the client, and computes
cosine similarity. It covers four scenarios:

1. **`testSimilarDocumentsScoreHigherThanUnrelated`** — the two dog documents
   score much higher than a dog-vs-finance pair.
2. **`testSelfSimilarityIsMaximal`** — a document compared with itself scores
   exactly `1.0` and dominates every cross-document score.
3. **`testRankingBySimilarity`** — ranking all candidates against document 0
   places the other dog document (id 1) first.
4. **`testArtificialDocumentSimilarity`** — an un-indexed ("artificial") dog
   document is more similar to the dog document than to the finance document.

### Observed values

Using the OpenSearch `standard` analyzer (no stop-word removal), the tf-idf +
cosine computation yields:

| Comparison | Cosine similarity |
| --- | --- |
| dog A vs dog B (similar) | ≈ 0.59 |
| dog A vs finance (unrelated) | ≈ 0.08 |
| dog A vs cat (weak overlap via common words) | ≈ 0.21 |
| dog A vs bonds (unrelated) | ≈ 0.04 |
| dog A vs itself (self) | = 1.00 |
| artificial dog vs dog A | ≈ 0.53 |
| artificial dog vs finance | ≈ 0.05 |

The similar and self comparisons dominate the unrelated ones by a wide margin,
confirming that the retrieved statistics are sufficient to decide lexical
similarity on the client.

### Running the test

```
./gradlew :server:internalClusterTest --tests "org.opensearch.action.termvectors.LexicalSimilarityIT"
```

> Note: JDK 21 is the minimum supported build JDK, and the build resolves
> dependencies from the OpenSearch Maven mirror, so an environment with network
> access to that mirror is required to execute the Gradle task.

## Conclusion

`more_like_this` = tf-idf term selection → Boolean query. To *compare* documents
rather than query with them, skip the query step: use
`_termvectors` / `_mtermvectors` with `term_statistics`, `field_statistics`
(and `dfs` on multi-shard indices) to obtain `tf`, `doc_freq`, and `ttf`,
reconstruct tf-idf vectors, and compute cosine (or Jaccard) similarity on the
client. The proof-of-concept test demonstrates this end to end.
