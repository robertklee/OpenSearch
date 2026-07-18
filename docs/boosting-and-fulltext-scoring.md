# Term & Field Boosting and Full-Text Query Evaluation in OpenSearch

> Engineering reference for understanding how boosting works end-to-end, from the
> request DSL down to Lucene scoring. Written to support work on generating a
> "boost string" (the `field^boost` / `term^boost` mini-syntax) programmatically.

All file references point at `server/src/main/java/org/opensearch/...` in this
repository unless otherwise noted. Line numbers are approximate and may drift.

---

## 1. Terminology and the three places "boost" lives

There are three conceptually distinct boosts. Conflating them is the single
biggest source of confusion, so pin them down first.

| Boost kind | Where it is written | What it multiplies | Applied in code |
| --- | --- | --- | --- |
| **Query-level boost** | The `"boost"` JSON field on any query clause (e.g. `{"match": {"title": {"query": "x", "boost": 2}}}`) | The whole clause's score | `AbstractQueryBuilder.toQuery` wraps in `BoostQuery` |
| **Field boost** | The `field^boost` syntax in a `fields` array, or `field(name, boost)` API (e.g. `"fields": ["title^3", "body"]`) | One field's sub-query inside a multi-field query | `QueryParserHelper.parseFieldsAndWeights` + `BoostQuery` per field |
| **Term boost** | The `term^boost` syntax *inside a query string* (e.g. `query_string: "quick^2 brown"`) | One term (or phrase) inside the classic Lucene grammar | Lucene's classic `QueryParser` grammar |

A fourth, legacy mechanism — **index-time field boost** in the mapping
(`{"type":"text","boost":2}`) — still parses but is deprecated and effectively
translated into a query-time boost. Lucene removed true index-time boosts; do
not design new behavior around them.

These boosts **compose multiplicatively**. A term with `^2` inside a query
string, running against a field declared `title^3`, and inside a clause with
`"boost": 1.5`, yields an effective multiplier of `2 × 3 × 1.5 = 9` on that
term's contribution (each layer wraps the previous in a `BoostQuery`, and nested
`BoostQuery` boosts multiply).

---

## 2. The "boost string" mini-syntax

Two related string grammars are relevant when generating boost strings.

### 2.1 Field-weight strings — `field^boost`

Used by `multi_match.fields`, `query_string.fields`,
`simple_query_string.fields`, and `combined_fields`. Parsing lives in
`index/search/QueryParserHelper.java#parseFieldsAndWeights`:

```java
public static Map<String, Float> parseFieldsAndWeights(List<String> fields) {
    final Map<String, Float> fieldsAndWeights = new HashMap<>();
    for (String field : fields) {
        int boostIndex = field.indexOf('^');
        String fieldName;
        float boost = 1.0f;
        if (boostIndex != -1) {
            fieldName = field.substring(0, boostIndex);
            boost = Float.parseFloat(field.substring(boostIndex + 1));
        } else {
            fieldName = field;
        }
        if (fieldsAndWeights.containsKey(field)) {   // NB: duplicate handling
            boost *= fieldsAndWeights.get(field);
        }
        fieldsAndWeights.put(fieldName, boost);
    }
    return fieldsAndWeights;
}
```

Rules a generator must respect:

- The delimiter is a single caret `^`. Everything before it is the field name
  (which may itself be a wildcard pattern, e.g. `title.*^2`), everything after
  is parsed with `Float.parseFloat` — so `2`, `2.0`, `0.35`, `1e1` are all valid.
- **No boost caret** means an implicit weight of `1.0`.
- A negative parse is *not* rejected here (it is rejected later at the builder
  API via `checkNegativeBoost`); generators should emit non-negative values.
- Duplicate/collision behavior is quirky: the `containsKey` check compares the
  raw `field` string (including the caret), while the map is keyed by
  `fieldName`. In practice this multiplies boosts only in narrow cases, and the
  more robust multiplication happens later in `resolveMappingFields`. Prefer
  emitting each field once.

### 2.2 Wildcard field expansion

After `parseFieldsAndWeights`, `QueryParserHelper.resolveMappingFields` /
`resolveMappingField` expand wildcard field names against the mapping and
propagate the weight to every concrete field they resolve to. When the same
concrete field is produced by multiple patterns, weights **multiply**:

```java
float boost = field.getValue();
if (resolvedFields.containsKey(field.getKey())) {
    boost *= resolvedFields.get(field.getKey());   // multiply on collision
}
resolvedFields.put(field.getKey(), boost);
```

Two special sentinels matter: an all-fields wildcard (`*`) is detected via
`hasAllFieldsWildcard`, and a per-field boost is only meaningful once resolved
to a real, searchable text field.

### 2.3 Query-string term boosts — `term^boost`

Inside a `query_string` query text, `^` is part of Lucene's **classic query
parser grammar**, not OpenSearch code. `index/search/QueryStringQueryParser`
extends `XQueryParser` (→ Lucene `QueryParser`), which tokenizes
`quick^2`, `"quick brown"^1.5`, `title:quick^2`, `[1 TO 5]^2`, etc., and attaches
the boost to the produced `TermQuery`/`PhraseQuery`/... via a wrapping
`BoostQuery`. OpenSearch does not override `newTermQuery`, so this passes through
Lucene unchanged.

Important asymmetry: **`simple_query_string` does NOT support `^` inside the
query text.** In simple_query_string, boosting is only available through the
`fields` array. A boost-string generator must therefore know which query type it
is targeting.

---

## 3. Query-level boost: how it becomes a Lucene query

Every query builder extends `index/query/AbstractQueryBuilder`. The `boost`
field is declared and validated there.

```java
public static final float DEFAULT_BOOST = 1.0f;
public static final ParseField BOOST_FIELD = new ParseField("boost");
protected float boost = DEFAULT_BOOST;

protected final void checkNegativeBoost(float boost) {
    if (Float.compare(boost, 0f) < 0) {
        throw new IllegalArgumentException(
            "negative [boost] are not allowed ... use a value between 0 and 1 to deboost");
    }
}
```

The wrapping happens in the `final` `toQuery`, which is why *every* clause
uniformly supports `boost`:

```java
public final Query toQuery(QueryShardContext context) throws IOException {
    Query query = doToQuery(context);           // subclass builds the base query
    if (query != null) {
        if (boost != DEFAULT_BOOST) {
            if (query instanceof MatchNoDocsQuery == false) {
                query = new BoostQuery(query, boost);   // <-- score multiplier
            }
        }
        if (queryName != null) {
            context.addNamedQuery(queryName, query);
        }
    }
    return query;
}
```

Key semantics:

- **`boost == 1.0` is a no-op** — no `BoostQuery` is created.
- **A `MatchNoDocsQuery` is never wrapped** (nothing to score).
- **Deboosting** uses `0 <= boost < 1`. Negative boosts are illegal.
- `BoostQuery(inner, b)` multiplies the inner query's score by `b`. Nested
  `BoostQuery` boosts multiply, which is how the three boost layers compound.
- Serialization: `boost` is read/written first in `writeTo`/stream ctor and
  round-trips through XContent via `declareStandardFields` →
  `parser.declareFloat(QueryBuilder::boost, BOOST_FIELD)` and
  `printBoostAndQueryName`.
- Rewrite preservation: `AbstractQueryBuilder.rewrite` copies a non-default
  boost onto the rewritten builder *only if the rewrite did not already set one*
  (`if (boost() != DEFAULT_BOOST && rewritten.boost() == DEFAULT_BOOST)`), so
  boosts survive query rewriting without being double-applied.

---

## 4. Field boosting across multiple fields (multi_match)

`index/query/MultiMatchQueryBuilder` holds the per-field weights in
`Map<String, Float> fieldsBoosts` (populated by `field(name)` /
`field(name, boost)` or the `fields` array). The `type` selects a strategy,
each carrying a default `tieBreaker`:

| `type` | Underlying match type | Default tie-breaker | Scoring intent |
| --- | --- | --- | --- |
| `best_fields` (default) | BOOLEAN | 0.0 | Best single field dominates (dis-max) |
| `most_fields` | BOOLEAN | 1.0 | Sum of all field scores |
| `cross_fields` | BOOLEAN | 0.0 | Terms blended across fields (shared stats) |
| `phrase` | PHRASE | 0.0 | Best phrase field |
| `phrase_prefix` | PHRASE_PREFIX | 0.0 | Best phrase-prefix field |
| `bool_prefix` | BOOLEAN_PREFIX | 1.0 | Sum, last term as prefix |

The build happens in `index/search/MultiMatchQuery`. For the per-field
strategies, each field's sub-query is boosted individually and then combined:

```java
// buildFieldQueries(...)
float boostValue = fieldNames.getOrDefault(fieldName, 1.0f);
Query query = parse(type.matchQueryType(), fieldName, value);
query = Queries.maybeApplyMinimumShouldMatch(query, minimumShouldMatch);
if (query != null && boostValue != AbstractQueryBuilder.DEFAULT_BOOST
        && query instanceof MatchNoDocsQuery == false) {
    query = new BoostQuery(query, boostValue);   // field boost
}
```

```java
// combineGrouped(...)
if (groupQuery.isEmpty())   return zeroTermsQuery();
if (groupQuery.size() == 1) return groupQuery.get(0);
return new DisjunctionMaxQuery(groupQuery, tieBreaker);
```

So for `best_fields`/`most_fields`/`phrase*` the result is a
`DisjunctionMaxQuery` over per-field `BoostQuery(subquery, fieldWeight)`.

### 4.1 cross_fields and BlendedTermQuery

`cross_fields` is special: instead of scoring each field independently (which
penalizes rare terms unevenly because IDF differs per field), it **blends term
statistics across fields** using `lucene/queries/BlendedTermQuery`. It builds a
`Term[]` across fields plus a parallel `float[] boosts`, using the maximum
document frequency across fields as a shared statistic so a term is scored
consistently regardless of which field it landed in. Field weights become the
per-term `boosts` entries, and results are still combined with a
`DisjunctionMaxQuery` using the tie-breaker. This is why cross_fields wants
fields that share an analyzer.

---

## 5. How a single full-text (match) query is built

`index/search/MatchQuery` (inner `MatchQueryBuilder extends` Lucene's
`QueryBuilder`) turns analyzed text into a Lucene query:

1. **Analysis.** The field's search analyzer (or quote analyzer for phrases,
   or an explicit analyzer) tokenizes the input into a `TokenStream`.
2. **Type dispatch** (`MatchQuery.Type`): `BOOLEAN`, `PHRASE`, `PHRASE_PREFIX`,
   `BOOLEAN_PREFIX`.
3. **Term construction.** `newTermQuery` produces a `TermQuery`, or a
   `FuzzyQuery` when `fuzziness` is set (honoring `prefix_length`,
   `max_expansions`, `transpositions`, `fuzzy_rewrite`).
4. **Combination.**
   - `BOOLEAN`: terms go into a `BooleanQuery`, each clause `SHOULD` (operator
     `OR`, the default) or `MUST` (operator `AND`). `minimum_should_match` is
     applied via `Queries.maybeApplyMinimumShouldMatch`.
   - Synonyms (position increment 0) at a position become a `SynonymQuery`
     rather than separate clauses.
   - `PHRASE`/`PHRASE_PREFIX`: a `PhraseQuery` (optionally with slop / a prefix
     final term).
5. **Zero-terms handling.** If analysis yields no tokens, `zero_terms_query`
   decides between returning `null`/nothing (`NONE`), a match-all (`ALL`), or a
   `MatchNoDocsQuery` (`NULL`).

`minimum_should_match` supports absolute (`2`), negative (`-1`), percentage
(`75%`), and conditional (`3<90%`) forms — parsed in
`common/lucene/search/Queries#calculateMinShouldMatch`.

---

## 5A. Multiple-word clauses and the analyzer

This is the most common point of confusion when reasoning about boosts: **a
boost is attached to a whole clause, never to individual analyzed tokens.** The
analyzer runs first and decides *how many tokens* a piece of text becomes; the
boost then multiplies the score of the single Lucene query that those tokens
were assembled into. So the boost value itself does not "vary" per word — but
the *number of tokens* the analyzer emits changes how many term scores get
summed inside the clause, which is what makes the effective, observed score move
around across analyzers.

### 5A.1 Where the boost lands relative to tokenization

Order of operations for `{"match": {"title": {"query": "quick brown fox", "boost": 2}}}`:

1. The `title` search analyzer tokenizes `"quick brown fox"` → e.g. tokens
   `[quick, brown, fox]`.
2. `MatchQuery` builds **one** query out of those tokens (§5) — for the default
   `BOOLEAN`/`OR` case a `BooleanQuery` with three `SHOULD` `TermQuery` clauses.
3. `AbstractQueryBuilder.toQuery` wraps that whole `BooleanQuery` in a single
   `BoostQuery(booleanQuery, 2.0)` (§3).

The `2.0` multiplies the *combined* BooleanQuery score, i.e. it multiplies the
sum of the per-term BM25 scores — it is **not** applied three times, once per
word. The same is true of a field boost (`title^2`) and, for `query_string`, a
`term^boost`. The only difference between those three layers is *which* query
node gets wrapped, not how the multiplication works.

### 5A.2 How a clause's internal score depends on token count

Because a `BooleanQuery` **sums** its matching `SHOULD`/`MUST` clause scores
(§6.1), the number of tokens directly changes the base (pre-boost) score:

- More tokens that match → more per-term BM25 scores summed → larger base score
  → a fixed boost multiplier yields a larger absolute contribution.
- A single token (e.g. `keyword`) → the clause degenerates to one `TermQuery`,
  so the boost multiplies exactly one BM25 score.
- Tokens sharing a position (synonyms, some decompounders) collapse into a
  `SynonymQuery` for that position rather than adding independent clauses, so
  they do **not** inflate the sum the way distinct positions do.

Consequence for a boost generator: the *same* `^2` behaves very differently on a
`keyword` field (doubles one term score) versus an `ngram` field (doubles a sum
of many overlapping-gram term scores). Budget boost magnitudes per-field with the
analyzer in mind, not as if every field produced the same number of terms.

### 5A.3 Operator, phrase, and `minimum_should_match` interactions

For a multi-word clause the *combination* of the analyzed tokens is governed by
`operator` / `type` / `minimum_should_match` **before** the boost is applied:

- `operator: OR` (default) → tokens are `SHOULD`; partial matches score
  (sum of the matching subset), then boosted.
- `operator: AND` → tokens are `MUST`; all must match, score is still the sum,
  then boosted.
- `type: phrase` → tokens become one `PhraseQuery`; the boost multiplies the
  single phrase score (position/slop-sensitive), not per token.
- `minimum_should_match` prunes which `SHOULD` clauses are required but does not
  change that the boost wraps the final assembled query.

---

## 5B. Analyzer families and their effect on boosting

The boost *syntax* is identical regardless of analyzer (see §5B.4); what changes
is the token stream the analyzer produces, and therefore the base score the
boost multiplies. The field's analyzer is resolved per field from the mapping
(`search_analyzer` for query text, `analyzer` at index time, `search_quote_analyzer`
for phrases), so different fields in the same `multi_match` can tokenize the same
input completely differently.

### 5B.1 `keyword` (no real analysis)

The `keyword` analyzer (and the `keyword` field type, which is not analyzed at
all) emits the **entire input as one token**. `"New York"` → a single term
`New York`.

- A multi-word query is one `TermQuery`; the boost multiplies one BM25 score.
- Matching is exact/whole-value — `quick` will not match a `keyword` term
  `quick brown fox`.
- `keyword` fields have `norms` off by default (§6), so length normalization is
  absent; scoring is effectively idf-driven and the boost is close to a plain
  linear multiplier on a per-term basis.
- Best when you want a boost to mean "this exact value is worth N×", e.g.
  `"fields": ["status^5"]` on a `keyword` `status` field.

### 5B.2 Language / stemming analyzers (`english`, `french`, …)

Language analyzers lowercase, remove stop words, and stem. This **reduces and
normalizes** the token count:

- Stop words are dropped: `"the quick brown fox"` with the `english` analyzer →
  `[quick, brown, fox]` (the `the` token disappears). Fewer `SHOULD` clauses
  means the summed base score omits the stop word's contribution before the
  boost applies — a boost cannot "recover" a removed token.
- Stemming folds variants to a root: `running`, `runs`, `ran` → `run`, which
  raises `tf`/`df` behavior and changes idf, indirectly shifting the base score
  the boost multiplies.
- The **query-time analyzer must match the index-time analyzer** for the terms
  to line up; a mismatch can make a boosted clause match nothing.
- For `cross_fields`, fields you blend should share the analyzer (§4.1) —
  otherwise the blended statistics and the effect of per-field boosts become
  inconsistent.

### 5B.3 N-gram / edge-n-gram analyzers

N-gram analyzers explode each word into many overlapping grams, so a short input
becomes **many tokens**:

- `"quick"` with a 2–3 char n-gram analyzer → `[qu, ui, ic, ck, qui, uic, ick]`.
- The `BOOLEAN`/`OR` clause is a `BooleanQuery` with one `SHOULD` per gram;
  scores **sum**. A single boost therefore multiplies a much larger base value,
  and near-matches (many shared grams) accumulate score even without a full
  match.
- This is why an n-gram field often *dominates* a `most_fields` sum unless you
  **deboost** it (e.g. `"fields": ["title^3", "title.ngram^0.3"]`): its many
  matching grams already produce a large summed base score.
- Prefer `best_fields` (dis-max, tie-breaker 0) or a small n-gram field weight
  when mixing n-gram and analyzed-text fields, so the gram sum does not swamp the
  exact-match field.

### 5B.4 Syntax is analyzer-independent

The three boost surfaces look the same no matter which analyzer the target field
uses; only the field you point them at (and thus its analyzer) differs:

```jsonc
// Query-level boost — multiplies the whole clause, any analyzer
{ "match": { "title": { "query": "quick brown fox", "boost": 2 } } }

// Field-weight strings (multi_match / query_string / simple_query_string) —
// mix analyzers across sub-fields and weight them differently
{ "multi_match": {
    "query": "quick brown fox",
    "type":  "most_fields",
    "fields": [ "title^3",          // language/standard analyzer, strong
                "title.keyword^5",   // keyword sub-field, exact whole value
                "title.ngram^0.3" ]  // n-gram sub-field, deboosted
} }

// Term/phrase boost inside query_string text (classic Lucene grammar only) —
// still one boost per assembled term/phrase, regardless of analyzer
{ "query_string": {
    "default_field": "title",
    "query": "quick^2 brown \"brown fox\"^1.5"
} }
```

Reminders that carry over from §2:

- `simple_query_string` does **not** honor `^` inside the query text; boost its
  sub-fields through the `fields` array instead.
- A boost on a `keyword`/exact sub-field multiplies one term score; the same
  numeric boost on an n-gram sub-field multiplies a sum of many gram scores, so
  the two are not comparable — tune them independently.

---

## 6. Scoring: how boosts turn into numbers (BM25)

The default similarity is **BM25**, wired in
`index/similarity/SimilarityService` (`DEFAULT_SIMILARITY = "BM25"`) and
constructed in `SimilarityProviders#createBM25Similarity` with defaults
`k1 = 1.2`, `b = 0.75`, `discountOverlaps = true`. Similarity is resolved
per-field via a `PerFieldSimilarityWrapper`, so a field can override it in its
mapping.

Conceptually, a single term's BM25 score in a document is:

```
score(term, doc) = idf(term) * ( tf / ( tf + k1 * (1 - b + b * dl/avgdl) ) )

idf = ln( 1 + (N - df + 0.5) / (df + 0.5) )
```

- `tf` — term frequency in the field of this doc.
- `df` — number of docs containing the term; `N` — total docs. Rarer terms
  score higher (higher idf).
- `dl` / `avgdl` — this field's length vs. average field length. Longer fields
  are penalized; `b` controls how strongly, `k1` controls tf saturation.
- **Field-length norms** encode `dl` at index time. They can be disabled with
  `norms: false` in the mapping (`omitNorms`) — off saves space but removes
  length normalization. Norms are on by default for `text`, off for `keyword`.

A `BoostQuery(inner, b)` multiplies the resulting score by `b`. Under the hood
the boost is threaded into `Similarity.scorer(boost, collectionStats,
termStats...)`, so all three boost layers ultimately arrive as one combined
multiplier on the per-term similarity score.

### 6.1 How compound queries combine term scores

- **`BooleanQuery`**: score = **sum** of the scores of matching `MUST` and
  `SHOULD` clauses. `FILTER` and `MUST_NOT` contribute no score.
- **`DisjunctionMaxQuery(tieBreaker)`**: score =
  `maxClauseScore + tieBreaker * sum(otherClauseScores)`.
  - `tieBreaker = 0` → pure "best field wins" (`best_fields`, `phrase`).
  - `tieBreaker = 1` → equivalent to summing every field (`most_fields`).
  - Intermediate values (e.g. `0.3`) let the best field dominate while other
    fields nudge the score.

Putting the multi_match pieces together, `best_fields` over `["title^3","body"]`
for query `q` yields roughly:

```
DisMax( tieBreaker = 0,
        BoostQuery( BooleanQuery(title:q...), 3.0 ),
        BooleanQuery(body:q...) )
```

and its score is the max of `3 × titleScore` and `bodyScore` (plus
`tieBreaker × the rest`).

---

## 7. Evaluation pipeline (request → score)

1. **Parse DSL** → a `QueryBuilder` tree (`fromXContent`), reading `boost`,
   `fields` (`field^weight`), `type`, `tie_breaker`, etc.
2. **Rewrite** (`QueryBuilder.rewrite`) — resolves wildcards/aliases, expands
   fields, preserves boosts.
3. **`toQuery`** — each builder's `doToQuery` produces a Lucene `Query`;
   `AbstractQueryBuilder.toQuery` wraps it in a query-level `BoostQuery` when
   needed. Field weights are wrapped per-field inside multi-field builders.
4. **Lucene `Query.rewrite`** — multi-term queries (prefix, fuzzy, wildcard)
   expand to their concrete term forms.
5. **`createWeight`** — gathers collection statistics (df, ttf) and builds the
   BM25 `SimScorer`, folding in the accumulated boost.
6. **`Scorer.score(doc)`** — per-segment, computes the BM25 score, combined by
   `BooleanQuery` (sum) / `DisjunctionMaxQuery` (max + tie-breaker) as above.

---

## 8. Practical rules for a boost-string generator

- Emit `field^weight` with a single caret; omit the caret for weight `1.0`.
  Weights are floats (`Float.parseFloat`), must be `>= 0`.
- Do not emit duplicate field entries; if two patterns can resolve to the same
  concrete field, know that resolved weights **multiply**.
- Wildcard field names (`title.*`, `*_text`, `*`) are allowed and expanded
  against the mapping — the weight propagates to every resolved field.
- `term^boost` inside the query text is only valid for `query_string` (classic
  Lucene grammar), **not** `simple_query_string`. For simple_query_string, only
  the `fields` array carries boosts.
- Choose `type` deliberately: `best_fields` (dis-max, tie-breaker 0) vs.
  `most_fields`/`bool_prefix` (sum, tie-breaker 1) vs. `cross_fields` (blended
  stats). The default `tie_breaker` comes from the `type` unless overridden.
- Remember the three boost layers multiply: query-level `"boost"`, field
  `field^weight`, and in-string `term^boost`. Budget total multipliers
  accordingly.
- Negative boosts are rejected at the builder API (`checkNegativeBoost`); use
  `0 < boost < 1` to deboost.

---

## 9. Key source references

| Concern | File |
| --- | --- |
| Query-level boost, validation, serialization, rewrite | `index/query/AbstractQueryBuilder.java` |
| `field^weight` parsing + wildcard expansion | `index/search/QueryParserHelper.java` |
| multi_match builder (`fields`, `type`, `tie_breaker`) | `index/query/MultiMatchQueryBuilder.java` |
| multi_match execution, per-field boost, dis-max, cross_fields | `index/search/MultiMatchQuery.java` |
| query_string builder / parser (`term^boost`, per-field boost) | `index/query/QueryStringQueryBuilder.java`, `index/search/QueryStringQueryParser.java` |
| simple_query_string parser (fields-only boosting) | `index/search/SimpleQueryStringQueryParser.java` |
| match query construction (boolean/phrase/fuzzy/synonym) | `index/search/MatchQuery.java` |
| BM25 defaults / per-field similarity | `index/similarity/SimilarityService.java`, `index/similarity/SimilarityProviders.java` |
| cross-field statistic blending | `lucene/queries/BlendedTermQuery.java` |
| minimum_should_match parsing | `common/lucene/search/Queries.java` |
