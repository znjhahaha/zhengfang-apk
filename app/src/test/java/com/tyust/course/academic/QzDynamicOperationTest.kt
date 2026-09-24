package com.tyust.course.academic

import org.junit.Assert.*
import org.junit.Test

class QzDynamicOperationTest {
    @Test fun generatedAndCommentedButtonsCannotOverwriteActualOperationArguments() {
        val html = """<script>
            var table = {
                "sAjaxSource": "/jsxsd/xsxkkc/xsxkXxxk",
                "aoColumns": [{"mDataProp":"kch"}]
            };
            function render(row) {
                row.button = "<a href=\"javascript:xsxkOper('"+row.jx0404id+"','','','"+row.jx02id+"','"+row.cfbs+"')\">选课</a>";
                // xsxkOper('comment-section','bad','bad','comment-course','bad');
                /* xsxkOper('block-section','bad','bad','block-course','bad'); */
            }
            function submit(jx0404id,kcid,cfbs) { xsxkOper(jx0404id,"","",kcid,cfbs); }
            function xsxkOper(jx0404id,xkzy,trjf,kcid,cfbs) {
                var param = "?kcid="+kcid+"&cfbs="+cfbs;
                var response = $.ajax({
                    url: "/jsxsd/xsxkkc/xxxkOper"+param,
                    data: {jx0404id:jx0404id,xkzy:xkzy,trjf:trjf}
                });
            }
            </script>"""
        val config = QzScriptParser.parse(html, "https://school.example/jsxsd/")!!
        assertEquals("https://school.example/jsxsd/xsxkkc/xsxkXxxk", config.listUrl)
        assertEquals(mapOf("xkzy" to "", "trjf" to ""), config.defaults)
        val values = mapOf("jx0404id" to "section", "kcid" to "course&1", "cfbs" to "null")
        assertEquals("https://school.example/jsxsd/xsxkkc/xxxkOper?kcid=course%261&cfbs=null",
            QzScriptParser.expand(config.submitUrl, values, url = true))
        assertEquals("section", QzScriptParser.expand(config.submitFields.getValue("jx0404id"), values))
    }
}
