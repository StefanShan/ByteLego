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

    private val matchConfigMap= mutableMapOf<Int,ConfigDataItem>()
    private var mLastClassName: String? = null

    /**
     * 判断是否命中规则（类名）
     *
     * @return Pair<匹配状态[MatchState], 命中的规则>
     */
    fun containsClass(cls: String): List<ConfigDataItem> {
        val realClass = cls.replace("/", ".")
        matchConfigMap.clear()
        configData?.forEachIndexed { index, configItem ->
            if (configItem.className == null || configItem.className.contains(realClass)){
                matchConfigMap[index] = configItem
            }
        }
        return matchConfigMap.values.toList()
    }

    /**
     * 判断是否命中规则（类的注解）
     *
     * @return Pair<匹配状态[MatchState], 命中的规则>
     */
    fun containsClassAnnotation(classAnnotation: String): List<ConfigDataItem> {
        val realAnnotation = classAnnotation.replace("/", ".")
        configData?.forEachIndexed { index, configItem ->
            if ((configItem.classAnnotation == null || configItem.classAnnotation == realAnnotation)
                && !matchConfigMap.contains(index)
            ) {
                matchConfigMap[index] = configItem
            }
        }
        return matchConfigMap.values.toList()
    }

    /**
     * 判断命中的规则中是否配置方法规则，如果未配置则认定为无效命中规则，将其移除
     */
    fun isEmptyForMatchMethodConfig(currentClassName: String): Boolean {
        if (currentClassName == mLastClassName){
            mLastClassName = currentClassName
            return false
        }
        for (index in matchConfigMap.keys) {
            val configItem = matchConfigMap[index]!!
            if (configItem.methodAnnotation.isNullOrBlank() && configItem.methodName.isNullOrEmpty()) {
                matchConfigMap.remove(index)
            }
        }
        return matchConfigMap.isEmpty()
    }

    private val hitConfigMap = mutableMapOf<Int, ConfigDataItem>()

    /**
     * 判断是否命中规则（方法名）
     */
    fun matchedMethod(methodName: String): Map<Int, ConfigDataItem> {
        hitConfigMap.clear()
        matchConfigMap.forEach { (index, configItem) ->
            if (configItem.methodName == null || configItem.methodName.contains(methodName)) {
                hitConfigMap[index] = configItem
            }
        }
        return hitConfigMap
    }

    /**
     * 判断是否命中规则（方法的注解）
     *
     * @return 匹配状态[MatchState]
     */
    fun matchMethodAnnotation(methodAnnotation: String): MutableMap<Int, ConfigDataItem> {
        matchConfigMap.forEach { (index, configItem) ->
            val realMethodAnnotation = "L${configItem.methodAnnotation};".replace(".", "/")
            if (realMethodAnnotation == methodAnnotation && !hitConfigMap.contains(index)) {
                hitConfigMap[index] = configItem
            }
        }
        return hitConfigMap
    }
}