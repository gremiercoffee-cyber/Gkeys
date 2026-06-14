package com.gremier.gkeys.ime.personalization

object ProfileMergeEngine {
    fun validate(profile: PersonalLanguageProfile): Boolean {
        if (profile.schemaVersion != PersonalLanguageProfile.SCHEMA_VERSION) return false
        if (profile.profileVersion < 0) return false
        return listOf(
            profile.customVocabulary.map { it.text },
            profile.neverAutocorrect,
            profile.phraseBoosts.map { it.text },
            profile.recentTopicBoosts.map { it.text },
        ).flatten().all { it.isNotBlank() && it.length <= 100 }
    }

    fun merge(
        current: PersonalLanguageProfile,
        proposed: PersonalLanguageProfile,
        now: Long = System.currentTimeMillis(),
    ): PersonalLanguageProfile {
        val nextVersion = maxOf(current.profileVersion + 1, proposed.profileVersion)
        return proposed.copy(
            schemaVersion = PersonalLanguageProfile.SCHEMA_VERSION,
            profileVersion = nextVersion,
            updatedAtMillis = now,
            customVocabulary = mergeWeighted(current.customVocabulary, proposed.customVocabulary),
            neverAutocorrect = (current.neverAutocorrect + proposed.neverAutocorrect)
                .map { it.lowercase() }.distinct().take(PersonalLanguageProfile.MAX_TERMS),
            phraseBoosts = mergeWeighted(current.phraseBoosts, proposed.phraseBoosts),
            nextWordPredictions = mergeNextWords(current.nextWordPredictions, proposed.nextWordPredictions),
            correctionPenalties = mergePenalties(current.correctionPenalties, proposed.correctionPenalties),
            recentTopicBoosts = mergeWeighted(current.recentTopicBoosts, proposed.recentTopicBoosts),
        )
    }

    private fun mergeWeighted(a: List<WeightedTerm>, b: List<WeightedTerm>): List<WeightedTerm> =
        (a + b)
            .groupBy { it.text.lowercase() }
            .map { (_, terms) ->
                val best = terms.maxBy { it.weight }
                best.copy(weight = terms.maxOf { it.weight }.coerceIn(-1.0, 1.0))
            }
            .sortedByDescending { it.weight }
            .take(PersonalLanguageProfile.MAX_TERMS)

    private fun mergeNextWords(a: List<NextWordBoost>, b: List<NextWordBoost>): List<NextWordBoost> =
        (a + b)
            .groupBy { it.previous.lowercase() to it.next.lowercase() }
            .map { (_, terms) -> terms.maxBy { it.weight } }
            .sortedByDescending { it.weight }
            .take(PersonalLanguageProfile.MAX_NEXT_WORDS)

    private fun mergePenalties(a: List<CorrectionPenalty>, b: List<CorrectionPenalty>): List<CorrectionPenalty> =
        (a + b)
            .groupBy { it.typed.lowercase() to it.correction.lowercase() }
            .map { (_, terms) -> terms.maxBy { it.weight } }
            .sortedByDescending { it.weight }
            .take(PersonalLanguageProfile.MAX_PENALTIES)
}
