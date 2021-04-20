package com.byte_stefan.bytelego

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.gradle.api.Project
import java.io.InputStreamReader
import java.util.*

data class ConfigDataItem(
    val classAnnotation: String?,
    val className: List<String>?,
    val methodAnnotation: String?,
    val methodName: List<String>?,
    val insertCodeConfig: InsertCodeConfig?
) {
    override fun toString(): String {
        return "ConfigDataItem(classAnnotation=$classAnnotation, className=$className, methodAnnotation=$methodAnnotation, methodName=$methodName, insertCodeConfig=$insertCodeConfig)"
    }
}

data class InsertCodeConfig(
    val className: String?,
    val onMethodAfter: String?,
    val onMethodBefore: String?
) {
    override fun toString(): String {
        return "InsertCodeConfig(className='$className', onMethodAfter='$onMethodAfter', onMethodBefore='$onMethodBefore')"
    }
}

enum class MatchState {
    NOT_CONFIG, //未配置
    NOT_MATCHED, //未匹配
    MATCHED //匹配
}

object ConfigManager {

    private var configData: ArrayList<ConfigDataItem>? = null

    //加载配置文件
    fun loadConfig(project: Project) {
        val configFile = project.rootProject.file("bytelego.json")
        if (configFile != null && FileManager.checkFileModified(configFile)) {
            //配置文件发生更改
            FileManager.markFileModified(configFile)
            //加载最新配置
            configFile.inputStream().use {
                val reader = InputStreamReader(it)
                val configJson = reader.readText()
                println("本地文件配置 = $configJson")
                configData = Gson().fromJson(
                    configJson,
                    object : TypeToken<ArrayList<ConfigDataItem>>() {}.type
                )

            }
        }
    }

    fun getConfig(): ArrayList<ConfigDataItem>? {
        return configData
    }

    private val matchConfigArray = mutableListOf<ConfigDataItem>()

    /**
     * 判断是否命中规则（类名）
     *
     * @return Pair<匹配状态[MatchState], 命中的规则>
     */
    fun containsClass(cls: String): Pair<MatchState, List<ConfigDataItem>> {
        val realClass = cls.replace("/", ".")
        matchConfigArray.clear()
        var classNotConfigTag = true
        configData?.forEach { configItem ->
            if (configItem.className.isNullOrEmpty()) {
                return@forEach
            }
            classNotConfigTag = false
            if (configItem.className.contains(realClass)) {
                matchConfigArray.add(configItem)
            }

        }
        return when {
            classNotConfigTag -> {
                Pair(MatchState.NOT_CONFIG, matchConfigArray)
            }
            matchConfigArray.isEmpty() -> {
                Pair(MatchState.NOT_MATCHED, matchConfigArray)
            }
            else -> {
                Pair(MatchState.MATCHED, matchConfigArray)
            }
        }
    }

    /**
     * 判断是否命中规则（类的注解）
     *
     * @return Pair<匹配状态[MatchState], 命中的规则>
     */
    fun containsClassAnnotation(classAnnotation: String): Pair<MatchState, List<ConfigDataItem>> {
        val realAnnotation = classAnnotation.replace("/", ".")
        var notClassAnnotationConfigTag = true
        configData?.forEach { configItem ->
            if (configItem.classAnnotation.isNullOrBlank()) {
                return@forEach
            }
            notClassAnnotationConfigTag = false
            if (configItem.classAnnotation == realAnnotation) {
                matchConfigArray.add(configItem)
            }
        }
        return when {
            notClassAnnotationConfigTag -> {
                Pair(MatchState.NOT_CONFIG, matchConfigArray)
            }
            matchConfigArray.isEmpty() -> {
                Pair(MatchState.NOT_MATCHED, matchConfigArray)
            }
            else -> {
                Pair(MatchState.MATCHED, matchConfigArray)
            }
        }
    }

    fun containsClassOrAnnotation(
        className: String,
        classAnnotation: String
    ): List<ConfigDataItem> {
        matchConfigArray.clear()
        val realClass = className.replace("/", ".").also {
            if (it.contains("$")) {
                it.substring(0, it.indexOf("$"))
            }
        }
        val realAnnotation = classAnnotation.replace("/", ".")
        configData?.forEach { configItem ->
            if ((configItem.className == null && configItem.classAnnotation == null)
                || configItem.className?.contains(realClass) == true
                || configItem.classAnnotation == realAnnotation
            ) {
                matchConfigArray.add(configItem)
            }
        }
        return matchConfigArray
    }

    /**
     * 判断命中的规则中是否配置方法规则，如果未配置则认定为无效命中规则，将其移除
     */
    fun isEmptyForMatchMethodConfig(): Boolean {
        for (index in matchConfigArray.indices) {
            val configItem = matchConfigArray[index]
            if (configItem.methodAnnotation.isNullOrBlank() && configItem.methodName.isNullOrEmpty()) {
                matchConfigArray.remove(configItem)
            }
        }
        return matchConfigArray.isEmpty()
    }

    /**
     * 判断是否命中规则（方法名）
     */
    fun matchedMethod(methodName: String): List<Pair<Int, ConfigDataItem>> {
        val matchIndexArray = mutableListOf<Pair<Int, ConfigDataItem>>()
        matchConfigArray.forEachIndexed { index, configItem ->
            if (configItem.methodName?.contains(methodName) == true) {
                matchIndexArray.add(Pair(index, configItem))
            }
        }
        return matchIndexArray
    }

    /**
     * 判断是否命中规则（方法的注解）
     *
     * @return 匹配状态[MatchState]
     */
    fun matchMethodAnnotation(methodAnnotation: String): List<Pair<Int, ConfigDataItem>> {
        val matchIndexArray = mutableListOf<Pair<Int, ConfigDataItem>>()
        matchConfigArray.forEachIndexed { index, configItem ->
            val realMethodAnnotation = "L${configItem.methodAnnotation}".replace(".","/")
            if (realMethodAnnotation == methodAnnotation) {
                matchIndexArray.add(Pair(index, configItem))
            }
        }
        return matchIndexArray
    }

    /**
     * 判断是否命中规则（方法名or方法注解）
     *
     * @return 匹配状态[MatchState]
     */
    fun matchMethodOrAnnotation(
        methodName: String,
        methodAnnotation: String
    ): List<Pair<Int, ConfigDataItem>> {
        val matchIndexArray = mutableListOf<Pair<Int, ConfigDataItem>>()
        matchConfigArray.forEachIndexed { index, configItem ->
            val realMethodAnnotation = "L${configItem.methodAnnotation};".replace(".", "/")
            if (realMethodAnnotation == methodAnnotation || configItem.methodName?.contains(
                    methodName
                ) == true
            ) {
                matchIndexArray.add(Pair(index, configItem))
            }
        }
        return matchIndexArray
    }

    fun getMatchConfigByIndex(index: Int): ConfigDataItem {
        return matchConfigArray[index]
    }
}