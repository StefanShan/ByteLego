package com.byte_stefan.bytelego

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        val cls = """com/byte_stefan/bytelego/MainActivity${'$'}onCreate${'$'}1"""
        val realClass = cls.replace("/", ".").let {
            it.substring(0, it.indexOf("$"))
        }
        print(realClass)
    }
}