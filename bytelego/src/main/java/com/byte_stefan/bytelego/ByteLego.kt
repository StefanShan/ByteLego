package com.byte_stefan.bytelego

import com.android.build.api.transform.Format
import com.android.build.api.transform.QualifiedContent
import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformInvocation
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryPlugin
import com.android.build.gradle.internal.pipeline.TransformManager
import org.apache.commons.io.FileUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.objectweb.asm.*
import org.objectweb.asm.commons.AdviceAdapter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object Config {
    var isForSDK = false
}

class ByteLegoPlugin : Plugin<Project>, Transform() {

    override fun apply(project: Project) {
        //判断是否为sdk引用该插件
        Config.isForSDK = project.plugins.hasPlugin(LibraryPlugin::class.java)
        //加载解析配置文件
        ConfigManager.loadConfig(project)
        project.extensions.getByType(BaseExtension::class.java).registerTransform(this)
    }

    override fun getName(): String = "ByteLego"

    override fun getInputTypes(): MutableSet<QualifiedContent.ContentType> =
        TransformManager.CONTENT_JARS

    override fun isIncremental(): Boolean = false

    override fun getScopes(): MutableSet<in QualifiedContent.Scope> = if (Config.isForSDK) {
        TransformManager.PROJECT_ONLY
    } else {
        TransformManager.SCOPE_FULL_PROJECT
    }

    override fun transform(transformInvocation: TransformInvocation) {
        super.transform(transformInvocation)

        //未配置插桩规则则直接return
        if (ConfigManager.getConfig().isNullOrEmpty()) {
            return
        }

        val outputProvider = transformInvocation.outputProvider

        if (!isIncremental) {
            outputProvider.deleteAll()
        }

        transformInvocation.inputs.forEach { transformInput ->

            //处理jar包中的类
            transformInput.jarInputs.forEach { jarInput ->
                val outputJarFile = outputProvider.getContentLocation(
                    jarInput.name,
                    jarInput.contentTypes,
                    jarInput.scopes,
                    Format.JAR
                )
                FileUtils.copyFile(jarInput.file, outputJarFile)
            }

            //处理文件夹中的类
            transformInput.directoryInputs.forEach { dirInput ->
                val outputDirFile = outputProvider.getContentLocation(
                    dirInput.name,
                    dirInput.contentTypes,
                    dirInput.scopes,
                    Format.DIRECTORY
                )
                val inputDirPath = dirInput.file.absolutePath
                val outputDirPath = outputDirFile.absolutePath

                FileUtils.listFiles(dirInput.file, arrayOf("class"), true).forEach { inputFile ->
                    //准备输出文件
                    val outputFilePath = inputFile.absolutePath.replace(inputDirPath, outputDirPath)
                    val outputFile = File(outputFilePath)
                    FileUtils.touch(outputFile)

                    //处理class文件，除了R文件生产的相关文件
                    if (inputFile.exists()
                        && inputFile.name.endsWith(".class")
                        && !inputFile.name.startsWith("R.class")
                        && !inputFile.name.startsWith("R$")
                    ) {

                        val inputStream = FileInputStream(inputFile)
                        val outputStream = FileOutputStream(outputFile)

                        //ASM处理class文件
                        val classReader = ClassReader(inputStream)
                        val classWriter = ClassWriter(ClassWriter.COMPUTE_MAXS)
                        classReader.accept(
                            LegoClassVisitor(classWriter),
                            ClassReader.EXPAND_FRAMES
                        )
                        outputStream.write(classWriter.toByteArray())

                        inputStream.close()
                        outputStream.close()

                    } else {
                        FileUtils.copyFile(inputFile, outputFile)
                    }
                }
            }
        }
    }
}

class LegoClassVisitor(visitor: ClassVisitor) : ClassVisitor(Opcodes.ASM7, visitor) {

    private var hitConfigList: List<ConfigDataItem>? = null
    private lateinit var currentClassName: String

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        println("类名 = $name")
        currentClassName = name
        hitConfigList = ConfigManager.containsClass(name)
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
        hitConfigList = ConfigManager.containsClassAnnotation(descriptor)
        return super.visitAnnotation(descriptor, visible)
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        println("方法名 = $name")
        //如果未配置class规则 or class命中的规则中有配置method规则的集合不为空，直接走LegoMethodVisitor
        //如果class未命中 or class命中了，但未配置method规则, 直接走super
        if (!hitConfigList.isNullOrEmpty() && !ConfigManager.isEmptyForMatchMethodConfig(currentClassName)) {
            val methodVisitor = cv.visitMethod(access, name, descriptor, signature, exceptions)
            return LegoMethodVisitor(api, methodVisitor, access, descriptor, name)
        }
        return super.visitMethod(access, name, descriptor, signature, exceptions)
    }
}

class LegoMethodVisitor(
    api: Int,
    methodVisitor: MethodVisitor,
    access: Int,
    descriptor: String?,
    name: String
) : AdviceAdapter(api, methodVisitor, access, name, descriptor) {

    private var hitConfigMap : Map<Int, ConfigDataItem> = ConfigManager.matchedMethod(name)

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
        hitConfigMap = ConfigManager.matchMethodAnnotation(descriptor)
        return super.visitAnnotation(descriptor, visible)
    }

    private fun assertCheck(insertCodeConfig: InsertCodeConfig?, ext: ()->Boolean): Boolean{
        return insertCodeConfig == null || insertCodeConfig.className.isNullOrBlank() || ext()
    }

    override fun onMethodEnter() {
        super.onMethodEnter()
        if (hitConfigMap.isNullOrEmpty()){
            return
        }
        for ((index, config) in hitConfigMap){
            val insertCodeConfig = config.insertCodeConfig
            if (assertCheck(insertCodeConfig){ insertCodeConfig?.onMethodBefore.isNullOrBlank() }) {
                return
            }
            val realInertClassName = insertCodeConfig?.className?.replace(".","/")
            mv.visitIntInsn(SIPUSH, index)
            mv.visitMethodInsn(
                INVOKESTATIC,
                realInertClassName,
                insertCodeConfig!!.onMethodBefore,
                "(I)V",
                false
            )
        }
    }

    override fun onMethodExit(opcode: Int) {
        super.onMethodExit(opcode)
        if (hitConfigMap.isNullOrEmpty()){
            return
        }
        for ((index, config) in hitConfigMap){
            val insertCodeConfig = config.insertCodeConfig
            if (assertCheck(insertCodeConfig){ insertCodeConfig?.onMethodAfter.isNullOrBlank() }) {
                return
            }
            val realInertClassName = insertCodeConfig?.className?.replace(".","/")
            if (opcode == Opcodes.RETURN || opcode == Opcodes.ATHROW) {
                mv.visitIntInsn(SIPUSH, index)
                mv.visitMethodInsn(
                    INVOKESTATIC,
                    realInertClassName,
                    insertCodeConfig!!.onMethodAfter,
                    "(I)V",
                    false
                )
            }
        }
    }
}