# Proof of Concept: Finding Dissimilar Documents with `must_not` and `boosting`

## Summary

A common question is: *"Can I leverage `MUST_NOT` terms to find dissimilar
documents?"* The short answer is **not on its own**. A `bool` query's
`must_not` clause is a **hard, binary exclusion** that runs in *filter context* —
it removes documents that match and contributes no score, so it cannot *rank*
documents by how dissimilar they are. To actually surface the *most dissimilar*
documents you want a **graded** signal, which the `boosting` query (optionally
using `more_like_this` as its negative clause) provides by **demoting** rather
than removing matching documents.

A proof-of-concept internal cluster test,
[`DissimilarDocumentsIT`](../server/src/internalClusterTest/java/org/opensearch/search/query/DissimilarDocumentsIT.java),
demonstrates and asserts both behaviors.

## Why `must_not` alone is not enough

In a `bool` query, `must_not` clauses run in **filter context**:

- They contribute **no score** — they only *exclude* documents that match.
- The result is boolean: a document either matches a `must_not` clause (excluded)
  or does not (kept). There is no gradient of "how dissimilar" a document is.

Relevant source:

- `server/src/main/java/org/opensearch/index/query/BoolQueryBuilder.java`
- `server/src/main/java/org/opensearch/index/search/MatchQuery.java`

So a query built only from `must_not` clauses returns every surviving document
with the same constant score:

```json
{
  "query": {
    "bool": {
      "must_not": [
        { "terms": { "field": ["dog", "barks", "mailman"] } }
      ]
    }
  }
}
```

This returns exactly the documents that contain **none** of those terms,
unranked. It is a useful *filter* ("show me documents unlike these"), but not a
*ranking* of the least-similar documents.

## Getting a graded "less like this" ranking

To keep every document but push the similar ones down, use the **`boosting`
query** instead of `must_not`. The `positive` clause selects the candidate set
and the `negative` clause + `negative_boost` (a multiplier `< 1`) demotes
documents that match it:

```json
{
  "query": {
    "boosting": {
      "positive": { "match_all": {} },
      "negative": { "terms": { "field": ["dog", "barks", "mailman"] } },
      "negative_boost": 0.1
    }
  }
}
```

Now nothing is excluded — the matching ("similar") documents simply sink to the
bottom of the ranking, and the dissimilar documents rise to the top.

Relevant source:

- `server/src/main/java/org/opensearch/index/query/BoostingQueryBuilder.java`
  (implemented on top of Lucene's `org.apache.lucene.queries.function.FunctionScoreQuery`)

### Ranking by dissimilarity to a seed document

The negative clause can be a `more_like_this` (MLT) query seeded from a
reference document. Documents are then demoted in proportion to how much they
resemble the seed, so the ranking runs from *most dissimilar to the seed* down
to the seed's nearest neighbor:

```json
{
  "query": {
    "boosting": {
      "positive": { "match_all": {} },
      "negative": {
        "more_like_this": {
          "fields": ["field"],
          "like": [{ "_index": "test", "_id": "0" }],
          "include": true,
          "min_term_freq": 1,
          "min_doc_freq": 1,
          "max_query_terms": 25
        }
      },
      "negative_boost": 0.1
    }
  }
}
```

Note that MLT's own `unlike` parameter is implemented internally as `MUST_NOT`
clauses on the top `unlike` terms, so it *demotes/excludes* rather than producing
a pure "most dissimilar" ordering. For a smooth dissimilarity ranking, the
`boosting` + `negative` approach above is the more direct tool.

## Alternatives for true semantic dissimilarity

If "dissimilar" means semantic (vector) distance rather than term overlap, a
k-NN / neural query sorted by the *farthest* vectors is the appropriate tool,
since `must_not` and `terms` operate only on discrete term matching. The
companion PoC
[`docs/lexical-similarity-poc.md`](./lexical-similarity-poc.md) shows how to
compute a lexical similarity value on the client from Term Vectors API
statistics; the same tf-idf vectors can be turned into a dissimilarity score
(for example `1 - cosine`) to rank the least-similar documents.

## Proof-of-concept test

The test builds a five-document corpus in a single-shard index (so scores are
global and deterministic):

| id | topic | text (abbreviated) |
| --- | --- | --- |
| 0 | dogs | "the quick brown **dog** runs fast … **barks** at the **mailman**" |
| 1 | dogs | "a happy brown **dog** **barks** and runs quickly … the **mailman**" |
| 2 | finance | "quarterly revenue growth … **stock price** higher" |
| 3 | cats | "the **cat** sleeps on the warm **windowsill** …" |
| 4 | finance | "investors weigh **interest rate** decisions and **bond** market **yields**" |

The two dog documents (ids 0 and 1) are treated as the "similar" set. The test
covers four scenarios:

1. **`testMustNotHardExcludesSimilarDocuments`** — a `must_not` on the dog terms
   returns exactly the non-dog documents (ids 2, 3, 4); the dog documents are
   excluded entirely.
2. **`testMustNotProducesNoSimilarityRanking`** — every surviving document from a
   `must_not`-only query shares the identical constant score, confirming there is
   no dissimilarity gradient.
3. **`testBoostingQueryRanksDissimilarDocumentsFirst`** — a `boosting` query with
   a small `negative_boost` keeps all five documents, ranks a dissimilar
   (non-dog) document first, and demotes the two dog documents to the bottom with
   a positive (non-zero) score.
4. **`testBoostingWithMoreLikeThisRanksBySeedDissimilarity`** — using
   `more_like_this` seeded from document 0 as the negative clause ranks the
   document least like the seed first and demotes the seed and its nearest
   neighbor (the two dog documents) to the bottom.

### Running the test

```
./gradlew :server:internalClusterTest --tests "org.opensearch.search.query.DissimilarDocumentsIT"
```

> Note: JDK 21 is the minimum supported build JDK, and the build resolves
> dependencies from the OpenSearch Maven mirror, so an environment with network
> access to that mirror is required to execute the Gradle task.

## Conclusion

`must_not` answers "exclude documents like these" — a hard, unranked filter. To
answer "rank documents by how *un*like these they are," demote instead of
exclude: use a `boosting` query with a `negative` clause (a `terms` query, or
`more_like_this` seeded from a reference document) and a `negative_boost < 1`.
For semantic dissimilarity, rank by vector distance with a k-NN query. The
proof-of-concept test demonstrates the `must_not` vs `boosting` distinction end
to end.
