package com.m57.hermescontrol.ui.chat

import com.hrm.latex.parser.LatexParser
import com.hrm.latex.parser.model.LatexNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexDependencyTest {
    @Test
    fun mathClassCommand_preservesExplicitAtomClass() {
        val node =
            LatexParser()
                .parse("""\mathbin{R}""")
                .children
                .single()

        assertTrue(node is LatexNode.MathClass)
        node as LatexNode.MathClass
        assertEquals(LatexNode.MathClass.AtomClass.BIN, node.atomClass)
    }
}
