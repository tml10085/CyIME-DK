package com.kingzcheung.xime.settings

/**
 * 不经 Rime 组字的语言：字母像英文一样逐字上屏（韩语按两벌식拼成音节，见 [com.kingzcheung.xime.service.HangulComposer]）。
 * 每种语言一个只标语言的入口方案（assets/rime_letters/<schemaId>.schema.yaml）和一个键盘（xime.yaml 的 [keyboard]）。
 * 补全词表是商店里的可选下载（[wordsModel]），开启语言时自动下载；没下载时照样能打字，只是没有补全。
 * 都默认不启用：在「语言与方案」打开时才加入方案列表并部署。
 */
enum class LetterLanguage(
    val language: InputLanguage,
    val schemaId: String,
    /** 实体键盘浮条和语音条上的语言标记。 */
    val badge: String,
    val keyboard: String,
    /** a–z 之外，这个键盘上点按或长按打出的字母（含大写）。 */
    val extraLetters: String,
    /** 补全词表在商店里的模型 id；null 表示没有商店下载（韩语无补全，丹麦语随包内置）。 */
    val wordsModel: String?,
    /** 随包内置的补全词表 assets 文件名；非空时直接从安装包读取，不走商店下载。 */
    val bundledWordsAsset: String? = null,
) {
    DANISH(InputLanguage.DANISH, "danish", "DA", "qwerty_danish", "åæøéäöüáóúÅÆØÉÄÖÜÁÓÚ", null, "cyime-words-da-v1.txt"),
    SPANISH(InputLanguage.SPANISH, "spanish", "ES", "qwerty_spanish", "áéíóúüñÁÉÍÓÚÜÑ", "words-es"),
    GERMAN(InputLanguage.GERMAN, "german", "DE", "qwerty_german", "äöüßÄÖÜ", "words-de"),
    FRENCH(InputLanguage.FRENCH, "french", "FR", "qwerty_french",
        "àâæçéèêëîïôœùûüÿÀÂÆÇÉÈÊËÎÏÔŒÙÛÜŸ", "words-fr"),
    ITALIAN(InputLanguage.ITALIAN, "italian", "IT", "qwerty_italian", "àèéìíòóùúÀÈÉÌÍÒÓÙÚ", "words-it"),
    RUSSIAN(InputLanguage.RUSSIAN, "russian", "RU", "jcuken_russian",
        "абвгдеёжзийклмнопрстуфхцчшщъыьэюяАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ", "words-ru"),
    KOREAN(InputLanguage.KOREAN, "korean", "한", "dubeolsik_korean",
        "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎㅏㅐㅑㅒㅓㅔㅕㅖㅗㅛㅜㅠㅡㅣ", null);

    /** 词表文件名带版本；换新版时改这里和商店目录。 */
    val wordsFile: String? get() = bundledWordsAsset ?: wordsModel?.let { "cyime-$it-v1.txt" }

    companion object {
        /** 所有语言键盘上的非 a–z 字母，键帽随 Shift 改大小写时用。 */
        val allExtraLetters: String = entries.joinToString("") { it.extraLetters }

        fun of(language: InputLanguage): LetterLanguage? = entries.firstOrNull { it.language == language }
        fun ofSchema(schemaId: String): LetterLanguage? = entries.firstOrNull { it.schemaId == schemaId }
        val schemaIds: List<String> = entries.map { it.schemaId }
        val keyboards: Set<String> = entries.mapTo(mutableSetOf()) { it.keyboard }
    }
}
