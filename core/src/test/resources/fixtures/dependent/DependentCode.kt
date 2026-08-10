package fixture.dependent

import com.google.common.base.Strings

fun shout(words: List<String>): String = Strings.nullToEmpty(words.firstOrNull()).uppercase()
