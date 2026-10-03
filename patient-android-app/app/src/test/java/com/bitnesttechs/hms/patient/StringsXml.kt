package com.bitnesttechs.hms.patient

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Reads `src/main/res/<qualifier>/strings.xml` as the text a user reads, for resource tests. */
object StringsXml {

    fun read(qualifier: String): Map<String, String> {
        val file = resolve("src/main/res/$qualifier/strings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return buildMap {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
                put(name, node.textContent.replace("\\'", "'").replace("\\\"", "\""))
            }
        }
    }

    /** Names marked translatable="false" (brand names and the like). */
    fun untranslatable(qualifier: String = "values"): Set<String> {
        val file = resolve("src/main/res/$qualifier/strings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return buildSet {
            for (index in 0 until nodes.length) {
                val attrs = nodes.item(index).attributes
                if (attrs.getNamedItem("translatable")?.nodeValue == "false") {
                    attrs.getNamedItem("name")?.nodeValue?.let { add(it) }
                }
            }
        }
    }

    fun resourceId(name: String): Int = R.string::class.java.getField(name).getInt(null)

    private fun resolve(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"), File("patient-android-app/app/$relative"))
        return candidates.firstOrNull { it.isFile }
            ?: error("cannot find $relative from ${File(".").absolutePath}")
    }
}
