package uz.kamoliddin.jasurassistant

object UzbekNumberReader {
    private val digits = mapOf(
        '0' to "nol", '1' to "bir", '2' to "ikki", '3' to "uch", '4' to "to'rt",
        '5' to "besh", '6' to "olti", '7' to "yetti", '8' to "sakkiz", '9' to "to'qqiz"
    )

    fun phone(number: String): String = number
        .filter { it.isDigit() || it == '+' }
        .mapNotNull { ch -> if (ch == '+') "plyus" else digits[ch] }
        .joinToString(" ")
}
