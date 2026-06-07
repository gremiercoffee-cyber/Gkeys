package com.gremier.gkeys.ime

object SwipeDecoder {

    private val dictionary = setOf(
        "the", "be", "to", "of", "and", "a", "in", "that", "have", "i",
        "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
        "this", "but", "his", "by", "from", "they", "we", "say", "her", "she",
        "or", "an", "will", "my", "one", "all", "would", "there", "their", "what",
        "so", "up", "out", "if", "about", "who", "get", "which", "go", "me",
        "when", "make", "can", "like", "time", "no", "just", "him", "know", "take",
        "people", "into", "year", "your", "good", "some", "could", "them", "see", "other",
        "than", "then", "now", "look", "only", "come", "its", "over", "think", "also",
        "back", "after", "use", "two", "how", "our", "work", "first", "well", "way",
        "even", "new", "want", "because", "any", "these", "give", "day", "most", "us",
        "hello", "world", "thanks", "thank", "please", "yes", "okay", "ok", "hey", "hi",
        "love", "great", "nice", "cool", "awesome", "sorry", "help", "need", "want", "going",
        "home", "house", "food", "water", "phone", "call", "text", "send", "read", "write",
        "play", "game", "music", "movie", "book", "school", "work", "job", "money", "buy",
        "sell", "shop", "store", "car", "drive", "walk", "run", "fast", "slow", "big",
        "small", "long", "short", "high", "low", "hot", "cold", "happy", "sad", "angry",
        "friend", "family", "mother", "father", "brother", "sister", "baby", "child", "man", "woman",
        "boy", "girl", "name", "place", "city", "town", "country", "world", "life", "live",
        "die", "born", "grow", "change", "start", "stop", "open", "close", "find", "lost",
        "right", "left", "next", "last", "best", "worst", "more", "less", "much", "many",
        "few", "every", "each", "both", "same", "different", "true", "false", "real", "fake",
        "tell", "talk", "speak", "listen", "hear", "watch", "show", "hide", "keep", "leave",
        "stay", "move", "turn", "put", "set", "let", "try", "ask", "answer", "question",
        "problem", "idea", "plan", "hope", "wish", "dream", "feel", "believe", "remember", "forget",
        "learn", "teach", "study", "test", "pass", "fail", "win", "lose", "fight", "peace",
        "war", "free", "pay", "cost", "price", "cheap", "rich", "poor", "young", "old",
        "today", "tomorrow", "yesterday", "morning", "night", "week", "month", "hour", "minute", "second",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "january", "february", "march", "april", "may", "june", "july", "august",
        "september", "october", "november", "december",
        "where", "why", "how", "when", "what", "who", "here", "there", "never", "always",
        "sometimes", "often", "once", "again", "still", "already", "yet", "soon", "late", "early",
        "email", "message", "chat", "online", "internet", "website", "google", "search", "click", "type",
        "password", "login", "account", "user", "profile", "photo", "video", "share", "post", "comment",
        "like", "follow", "block", "report", "delete", "edit", "save", "copy", "paste", "cut",
        "undo", "redo", "select", "clear", "reset", "update", "download", "upload", "install", "remove",
        "wait", "ready", "done", "finish", "begin", "continue", "break", "rest", "sleep", "wake",
        "eat", "drink", "cook", "clean", "wash", "wear", "dress", "shoes", "shirt", "pants",
        "dog", "cat", "bird", "fish", "tree", "flower", "sun", "moon", "star", "rain",
        "snow", "wind", "cloud", "sky", "sea", "lake", "river", "mountain", "beach", "park",
        "street", "road", "bridge", "building", "room", "door", "window", "floor", "wall", "bed",
        "table", "chair", "kitchen", "bathroom", "garden", "yard", "farm", "field", "fire", "light",
        "dark", "color", "red", "blue", "green", "yellow", "black", "white", "brown", "pink",
        "purple", "orange", "gray", "gold", "silver", "number", "letter", "word", "sentence", "story",
        "news", "paper", "magazine", "article", "report", "letter", "note", "list", "map", "guide",
        "doctor", "nurse", "hospital", "medicine", "health", "sick", "pain", "hurt", "safe", "danger",
        "police", "law", "court", "judge", "crime", "steal", "kill", "save", "protect", "attack",
        "power", "energy", "electric", "battery", "charge", "machine", "computer", "screen", "keyboard", "mouse",
        "program", "code", "data", "file", "folder", "document", "image", "sound", "voice", "noise",
        "quiet", "loud", "soft", "hard", "easy", "difficult", "simple", "complex", "quick", "slow",
        "beautiful", "pretty", "ugly", "clean", "dirty", "full", "empty", "heavy", "light", "strong",
        "weak", "smart", "stupid", "funny", "serious", "boring", "exciting", "interesting", "important", "special",
        "normal", "strange", "weird", "crazy", "perfect", "wrong", "correct", "maybe", "sure", "certain",
        "possible", "impossible", "enough", "almost", "exactly", "probably", "definitely", "actually", "really", "very",
        "too", "also", "only", "even", "just", "still", "already", "however", "though", "although",
        "because", "since", "until", "while", "during", "before", "after", "between", "among", "through",
        "under", "above", "below", "behind", "front", "inside", "outside", "around", "across", "along",
        "without", "within", "against", "toward", "towards", "upon", "onto", "off", "down", "up",
        "away", "back", "forward", "together", "alone", "anyone", "someone", "everyone", "nobody", "something",
        "anything", "everything", "nothing", "myself", "yourself", "himself", "herself", "itself", "ourselves", "themselves"
    )

    fun decode(path: List<Char>): String? {
        if (path.size < 2) return null
        var bestWord: String? = null
        var bestScore = Int.MAX_VALUE
        for (word in dictionary) {
            if (word.length < 2 || !matchesPath(word, path)) continue
            val score = path.size - word.length * 3 + kotlin.math.abs(word.length - path.distinct().size)
            if (score < bestScore) {
                bestScore = score
                bestWord = word
            }
        }
        return bestWord
    }

    private fun matchesPath(word: String, path: List<Char>): Boolean {
        var wi = 0
        for (c in path) {
            if (wi < word.length && c == word[wi]) wi++
        }
        return wi == word.length
    }
}
