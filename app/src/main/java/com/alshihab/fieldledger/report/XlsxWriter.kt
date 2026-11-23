package com.alshihab.fieldledger.report

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Minimal dependency-free .xlsx writer: one right-to-left sheet with styled title, header and totals rows. */
object XlsxWriter {
    private val numberRe = Regex("^-?[0-9][0-9,]*(\\.[0-9]+)?$")

    private fun esc(s: String) = buildString {
        for (ch in s) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> if (ch.code < 0x20 && ch != '\n' && ch != '\t') append(' ') else append(ch)
            }
        }
    }

    private fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, ('A' + r)); n = (n - 1) / 26 }
        return sb.toString()
    }

    // style ids: 0 normal, 1 title, 2 header, 3 total text, 4 number, 5 total number, 6 bold label
    private class Sheet {
        val rows = StringBuilder()
        var r = 0
        var maxCols = 1
        val merges = mutableListOf<String>()

        fun row(cells: List<Pair<String, Int>>, numeric: List<Boolean> = emptyList(), height: Int? = null) {
            r++
            maxCols = maxOf(maxCols, cells.size)
            rows.append("<row r=\"$r\"").append(if (height != null) " ht=\"$height\" customHeight=\"1\"" else "").append(">")
            cells.forEachIndexed { i, (v, st) ->
                val ref = colName(i) + r
                val isNum = numeric.getOrElse(i) { false } && numberRe.matches(v)
                if (isNum) {
                    val dec = v.contains('.')
                    val style = if (st == 3 || st == 5) (if (dec) 8 else 5) else (if (dec) 7 else 4)
                    rows.append("<c r=\"$ref\" s=\"$style\"><v>${v.replace(",", "")}</v></c>")
                } else if (v.isNotEmpty()) {
                    rows.append("<c r=\"$ref\" s=\"$st\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>")
                } else {
                    rows.append("<c r=\"$ref\" s=\"$st\"/>")
                }
            }
            rows.append("</row>")
        }

        fun mergeRow(width: Int) {
            if (width > 1) merges += "A$r:${colName(width - 1)}$r"
        }
    }

    fun write(d: ReportDoc, @Suppress("UNUSED_PARAMETER") hash: String, exportedText: String, out: File) = writeBook(listOf(d), exportedText, out)

    private fun sheetXml(d: ReportDoc, exportedText: String): String {
        val sh = Sheet()
        val width = maxOf(6, d.sections.maxOfOrNull { it.columns.size } ?: 6)
        sh.row(listOf("مكتب الشهاب للمقاولات العامة" to 1), height = 24); sh.mergeRow(width)
        sh.row(listOf(d.title to 1), height = 22); sh.mergeRow(width)
        d.headerLines().forEach { (k, v) -> sh.row(listOf(k to 6, v to 0)) }
        sh.row(listOf("تاريخ ووقت التصدير" to 6, exportedText to 0))
        sh.row(emptyList())
        for (sec in d.sections) {
            if (sec.title != null) { sh.row(listOf(sec.title to 6)); sh.mergeRow(width) }
            val numeric = sec.columns.map { it.numeric }
            sh.row(sec.columns.map { it.title to 2 }, height = 30)
            sec.rows.forEach { r -> sh.row(r.map { it to 0 }, numeric) }
            sec.totals.forEach { r -> sh.row(r.map { it to 3 }, numeric) }
            sec.notes.forEach { (k, v) -> sh.row(listOf(k to 6, v to 0)) }
            sh.row(emptyList())
        }
        d.signatures.forEach { (role, name) -> sh.row(listOf(role to 6, name to 0, "التوقيع:" to 0)) }

        val cols = StringBuilder("<cols>")
        for (i in 0 until maxOf(width, sh.maxCols)) cols.append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"${if (i == 1) 28 else 16}\" customWidth=\"1\"/>")
        cols.append("</cols>")
        val merges = if (sh.merges.isEmpty()) "" else
            "<mergeCells count=\"${sh.merges.size}\">" + sh.merges.joinToString("") { "<mergeCell ref=\"$it\"/>" } + "</mergeCells>"
        val orientation = if (d.landscape) "landscape" else "portrait"

        val sheetXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheetPr><pageSetUpPr fitToPage="1"/></sheetPr><sheetViews><sheetView rightToLeft="1" workbookViewId="0"/></sheetViews><sheetFormatPr defaultRowHeight="18"/>$cols<sheetData>${sh.rows}</sheetData>$merges<pageMargins left="0.4" right="0.4" top="0.5" bottom="0.5" header="0.3" footer="0.3"/><pageSetup paperSize="9" orientation="$orientation" fitToWidth="1" fitToHeight="0"/></worksheet>"""

        return sheetXml
    }

    fun writeBook(docs: List<ReportDoc>, exportedText: String, out: File) {
        val names = mutableSetOf<String>()
        val sheetNames = docs.map { d ->
            var base = d.title.map { ch -> if (ch in "[]:*?/\\") '-' else ch }.joinToString("").take(28)
            var n = base; var k = 2
            while (!names.add(n)) { n = base.take(25) + " " + k; k++ }
            esc(n)
        }
        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="2"><numFmt numFmtId="164" formatCode="#,##0"/><numFmt numFmtId="165" formatCode="#,##0.00"/></numFmts><fonts count="4"><font><sz val="11"/><name val="Arial"/></font><font><b/><sz val="14"/><color rgb="FF14228F"/><name val="Arial"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Arial"/></font><font><b/><sz val="11"/><color rgb="FF0F196E"/><name val="Arial"/></font></fonts><fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF0F196E"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFDDE1F7"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FF9A9BAD"/></left><right style="thin"><color rgb="FF9A9BAD"/></right><top style="thin"><color rgb="FF9A9BAD"/></top><bottom style="thin"><color rgb="FF9A9BAD"/></bottom><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="9"><xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf><xf numFmtId="0" fontId="2" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf><xf numFmtId="0" fontId="3" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf><xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf><xf numFmtId="164" fontId="3" fillId="3" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf><xf numFmtId="0" fontId="3" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment horizontal="right" vertical="center"/></xf><xf numFmtId="165" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf><xf numFmtId="165" fontId="3" fillId="3" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""

        val sheetOverrides = docs.indices.joinToString("") { i ->
            "<Override PartName=\"/xl/worksheets/sheet${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
        }
        val sheetsXml = docs.indices.joinToString("") { i -> "<sheet name=\"${sheetNames[i]}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }
        val sheetRels = docs.indices.joinToString("") { i ->
            "<Relationship Id=\"rId${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${i + 1}.xml\"/>"
        }
        val files = linkedMapOf<String, String>(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>$sheetOverrides<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>$sheetsXml</sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$sheetRels<Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""",
            "xl/styles.xml" to styles,
        )
        docs.forEachIndexed { i, d -> files["xl/worksheets/sheet${i + 1}.xml"] = sheetXml(d, exportedText) }
        ZipOutputStream(out.outputStream()).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
