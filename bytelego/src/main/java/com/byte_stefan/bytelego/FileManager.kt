package com.byte_stefan.bytelego

import org.gradle.api.Project
import java.io.File

object FileManager {

    private val modifiedHistory = mutableMapOf<String, Long>()

    fun checkFileModified(file: File): Boolean{
        val lastModifiedTime = modifiedHistory[file.absolutePath]?:0
        return lastModifiedTime < file.lastModified()
    }

    fun markFileModified(file: File){
        modifiedHistory[file.absolutePath] = file.lastModified()
    }
}