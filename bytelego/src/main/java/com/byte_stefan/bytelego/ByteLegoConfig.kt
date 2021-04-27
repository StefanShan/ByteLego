package com.byte_stefan.bytelego

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.gradle.api.Project
import org.yaml.snakeyaml.TypeDescription
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.Constructor
import java.io.InputStreamReader
import java.util.*

class RuleConfig{
    var rules: List<ConfigDataItem> ?= null

    override fun toString(): String {
        return "RuleConfig(rules=$rules)"
    }
}

class ConfigDataItem {
    var classAnnotation: String? = null
    var className: List<String>? = null
    var methodAnnotation: String? = null
    var methodName: List<String>? = null
    var insertCodeConfig: InsertCodeConfig? = null

    override fun toString(): String {
        return "ConfigDataItem(classAnnotation=$classAnnotation, className=$className, methodAnnotation=$methodAnnotation, methodName=$methodName, insertCodeConfig=$insertCodeConfig)"
    }
}

class InsertCodeConfig {

    var className: String? = null
    var onMethodAfter: String? = null
    var onMethodBefore: String? = null

    override fun toString(): String {
        return "InsertCodeConfig(className='$className', onMethodAfter='$onMethodAfter', onMethodBefore='$onMethodBefore')"
    }
}

object ConfigManager {

    private var configData: List<ConfigDataItem>? = null

    //加载配置文件
    fun loadConfig(project: Project) {
        val configFile = project.rootProject.file("byteRule.yaml")
        if (configFile != null && FileManager.checkFileModified(configFile)) {
            //配置文件发生更改
            FileManager.markFileModified(configFile)
            //加载最新配置
            val constructor = Constructor(RuleConfig::class.java)
            val yamlConfig = Yaml(constructor)
            val ruleConfig = configFile.inputStream().use {
                yamlConfig.load<RuleConfig>(it)
            }
            configData = ruleConfig.rules
            println("配置数据 = " + configData?.toString())
        }
    }

    fun getConfig(): List<ConfigDataItem>? {
        return configData
    }

    private val matchConfigMap= mutableMapOf<Int,ConfigDataItem>()
    private val hitConfigMap = mutableMapOf<Int, ConfigDataItem>()
    private var mLastClassName: String? = null

    /**
     * 筛选命中的规则（类名）
     *
     * @return 命中的规则
     */
    fun filterClassConfig(cls: String): List<ConfigDataItem> {
        val realClass = cls.replace("/", ".")
        matchConfigMap.clear()
        configData?.forEachIndexed { index, configItem ->
            if (configItem.className == null || configItem.className?.contains(realClass) == true){
                matchConfigMap[index] = configItem
            }
        }
        return matchConfigMap.values.toList()
    }

    /**
     * 筛选命中的规则（类的注解）
     *
     * @return 命中的规则
     */
    fun filterClassAnnotationConfig(classAnnotation: String): List<ConfigDataItem> {
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
        //如果是同一个类的不同方法，已经是命中的有效规则了，不需要再进行过滤
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

    /**
     * 判断是否命中规则（方法名）
     */
    fun matchedMethod(methodName: String): Map<Int, ConfigDataItem> {
        hitConfigMap.clear()
        matchConfigMap.forEach { (index, configItem) ->
            if (configItem.methodName == null || configItem.methodName?.contains(methodName) == true) {
                hitConfigMap[index] = configItem
            }
        }
        return hitConfigMap
    }

    /**
     * 判断是否命中规则（方法的注解）
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