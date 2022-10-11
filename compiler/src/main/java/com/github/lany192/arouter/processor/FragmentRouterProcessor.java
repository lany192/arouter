package com.github.lany192.arouter.processor;

import static com.alibaba.android.arouter.facade.enums.TypeKind.PARCELABLE;
import static com.alibaba.android.arouter.facade.enums.TypeKind.SERIALIZABLE;

import com.alibaba.android.arouter.facade.annotation.Autowired;
import com.alibaba.android.arouter.facade.annotation.Route;
import com.alibaba.android.arouter.facade.enums.TypeKind;
import com.github.lany192.arouter.Consts;
import com.github.lany192.arouter.TypeUtils;
import com.github.lany192.arouter.Utils;
import com.google.auto.service.AutoService;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import net.ltgt.gradle.incap.IncrementalAnnotationProcessor;
import net.ltgt.gradle.incap.IncrementalAnnotationProcessorType;

import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

@AutoService(Processor.class)
@IncrementalAnnotationProcessor(IncrementalAnnotationProcessorType.ISOLATING)
public class FragmentRouterProcessor extends BaseProcessor {
    private Types types;
    private TypeMirror iProvider = null;
    private TypeUtils typeUtils;
    private final ClassName arouterClassName = ClassName.get("com.alibaba.android.arouter.launcher", "ARouter");
    private ClassName PathsClassName;
    private final ClassName postcardClass = ClassName.get("com.alibaba.android.arouter.facade", "Postcard");

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        types = processingEnv.getTypeUtils();
        typeUtils = new TypeUtils(types, processingEnv.getElementUtils());
        iProvider = processingEnv.getElementUtils().getTypeElement(Consts.IPROVIDER).asType();
        PathsClassName = ClassName.get("com.alibaba.android.arouter", Utils.toUpperCaseFirstOne(module) + "Paths");
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> set = new LinkedHashSet<>();
        set.add(Route.class.getCanonicalName());
        set.add(Autowired.class.getCanonicalName());
        return set;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(Route.class);
        if (elements != null && !elements.isEmpty()) {
            Set<? extends Element> routeElements = roundEnv.getElementsAnnotatedWith(Route.class);
            for (Element element : routeElements) {
                if (isFragment(element)) { // Fragment
                    try {
                        List<MethodSpec> methods = createMethods(element);
                        methods.add(createPostcard(element));
                        createFragmentBuilder(element, methods);
                    } catch (Exception e) {
                        logger.error(e);
                    }
                }
            }
            logger.info("忽略异常提示");
            //这里要注意，要返回false，并且要放在Processor的前面，否则会影响arouter的Processor。
            return false;
        }
        return true;
    }

    /**
     * Postcard方法
     */
    private MethodSpec createPostcard(Element element) {
        MethodSpec.Builder builder = MethodSpec
                .methodBuilder("build")
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc("构建Fragment实例");
        Route route = element.getAnnotation(Route.class);
        String path = route.path().replace("/", "_").toUpperCase().substring(1);
        builder.addCode("$T postcard = $T.getInstance().build($T." + path + ");", postcardClass, arouterClassName, PathsClassName);
        for (Element field : element.getEnclosedElements()) {
            if (field.getKind().isField() && field.getAnnotation(Autowired.class) != null && !types.isSubtype(field.asType(), iProvider)) {
                Autowired autowired = field.getAnnotation(Autowired.class);
                builder.addCode(makeCode(field, autowired));
            }
        }
        builder.addCode("\nreturn ($T) postcard.navigation();", ClassName.get((TypeElement) element));
        builder.returns(ClassName.get((TypeElement) element));
        return builder.build();
    }

    private ClassName getFragmentBuilderName(Element element) {
        String simpleName = element.getSimpleName().toString().replace("Fragment", "");
        return ClassName.get(ClassName.get((TypeElement) element).packageName(), simpleName + "Builder");
    }

    private List<MethodSpec> createMethods(Element element) {
        List<MethodSpec> methods = new ArrayList<>();
        for (Element field : element.getEnclosedElements()) {
            if (field.getKind().isField() && field.getAnnotation(Autowired.class) != null && !types.isSubtype(field.asType(), iProvider)) {
                Autowired autowired = field.getAnnotation(Autowired.class);
                String fieldName = field.getSimpleName().toString();
                String key = StringUtils.isEmpty(autowired.name()) ? fieldName : autowired.name();
                TypeMirror typeMirror = field.asType();
                String typeName = field.asType().toString();

                MethodSpec.Builder builder = MethodSpec.methodBuilder(Utils.line2hump(key));
                builder.addModifiers(Modifier.PUBLIC);
                ParameterSpec parameterSpec;

                if (typeMirror.getKind().isPrimitive()) {
                    parameterSpec = ParameterSpec.builder(TypeName.get(typeMirror), key).build();
                } else {
                    //是否是泛型
                    if (typeName.contains("<") && typeName.contains(">")) {
                        int startIndex = typeName.indexOf("<");
                        int endIndex = typeName.indexOf(">");
                        String tmp = typeName.substring(startIndex + 1, endIndex);
                        int index = tmp.lastIndexOf(".");
                        ClassName className = ClassName.get(tmp.substring(0, index), tmp.substring(index + 1));
                        ClassName list = ClassName.get("java.util", "List");
                        parameterSpec = ParameterSpec.builder(ParameterizedTypeName.get(list, className), key).build();
                    } else {
                        if (typeName.contains(".")) {
                            int index = typeName.lastIndexOf(".");
                            ClassName className = ClassName.get(typeName.substring(0, index), typeName.substring(index + 1));
                            parameterSpec = ParameterSpec.builder(className, key).build();
                        } else {
                            parameterSpec = ParameterSpec.builder(Object.class, key).build();
                        }
                    }
                }
                builder.addParameter(parameterSpec);
                builder.addJavadoc(autowired.desc());
                builder.addCode("this." + key + " = " + key + ";");
                builder.addCode("\nreturn this;");
                builder.returns(getFragmentBuilderName(element));

                methods.add(builder.build());
            }
        }
        return methods;
    }

    private List<FieldSpec> createFields(Element element) {
        List<FieldSpec> fields = new ArrayList<>();
        for (Element field : element.getEnclosedElements()) {
            if (field.getKind().isField() && field.getAnnotation(Autowired.class) != null && !types.isSubtype(field.asType(), iProvider)) {
                Autowired autowired = field.getAnnotation(Autowired.class);
                String fieldName = field.getSimpleName().toString();
                String key = StringUtils.isEmpty(autowired.name()) ? fieldName : autowired.name();
                TypeMirror typeMirror = field.asType();
                String typeName = field.asType().toString();

                if (typeMirror.getKind().isPrimitive()) {
                    fields.add(FieldSpec
                            .builder(TypeName.get(typeMirror), key, Modifier.PRIVATE)
                            .addJavadoc(autowired.desc() + "\n")
                            .build());
                } else {
                    //是否是泛型
                    if (typeName.contains("<") && typeName.contains(">")) {
                        int startIndex = typeName.indexOf("<");
                        int endIndex = typeName.indexOf(">");
                        String tmp = typeName.substring(startIndex + 1, endIndex);
                        int index = tmp.lastIndexOf(".");
                        ClassName className = ClassName.get(tmp.substring(0, index), tmp.substring(index + 1));
                        ClassName list = ClassName.get("java.util", "List");

                        fields.add(FieldSpec
                                .builder(ParameterizedTypeName.get(list, className), key, Modifier.PRIVATE)
                                .addJavadoc(autowired.desc() + "\n")
                                .build());
                    } else {
                        if (typeName.contains(".")) {
                            int index = typeName.lastIndexOf(".");
                            ClassName className = ClassName.get(typeName.substring(0, index), typeName.substring(index + 1));
                            fields.add(FieldSpec
                                    .builder(className, key, Modifier.PRIVATE)
                                    .addJavadoc(autowired.desc() + "\n")
                                    .build());
                        } else {
                            fields.add(FieldSpec
                                    .builder(Object.class, key, Modifier.PRIVATE)
                                    .addJavadoc(autowired.desc() + "\n")
                                    .build());
                        }
                    }
                }
            }
        }
        return fields;
    }

    private String makeCode(Element field, Autowired autowired) {
        String fieldName = field.getSimpleName().toString();
        String key = StringUtils.isEmpty(autowired.name()) ? fieldName : autowired.name();
        String typeName = field.asType().toString();
        TypeMirror typeMirror = field.asType();
        TypeKind typeKind = TypeKind.values()[typeUtils.typeExchange(field)];

        String name = Utils.toUpperCaseFirstOne(typeName);
        String code = "";
        if (typeMirror.getKind().isPrimitive()) {
            logger.info("字段:" + fieldName + " -> 基本类型:" + name);
            code += "\npostcard.with" + name + "(\"" + key + "\", " + key + ");";
        } else {
            code = "\nif (" + key + " != null) {";
            if (typeKind == SERIALIZABLE) {
                logger.info("字段:" + fieldName + " -> Serializable类型:" + name);
                code += "\n    postcard.withSerializable(\"" + key + "\", (" + typeName + ")" + key + ");";
            } else if (typeKind == PARCELABLE) {
                logger.info("字段:" + fieldName + " -> Parcelable类型:" + name);
                code += "\n    postcard.withParcelable(\"" + key + "\", (" + typeName + ")" + key + ");";
            } else {
                logger.info("字段:" + fieldName + " -> 字段类型:" + name);
                if (typeName.startsWith("java.lang") || typeName.startsWith("java.util")) {
                    //是否是泛型
                    if (!typeName.contains("<") && typeName.contains(".")) {
                        int index = typeName.lastIndexOf(".");
                        name = Utils.toUpperCaseFirstOne(typeName.substring(index + 1));
                        code += "\n    postcard.with" + name + "(\"" + key + "\", " + key + ");";
                    } else {
                        code += "\n    postcard.withObject(\"" + key + "\", " + key + ");";
                    }
                } else {
                    code += "\n    postcard.withObject(\"" + key + "\", " + key + ");";
                }
            }
            code += "\n}";
        }
        return code;
    }

    private void createFragmentBuilder(Element element, List<MethodSpec> methods) throws Exception {
        ClassName className = getFragmentBuilderName(element);

        String simpleName = element.getSimpleName().toString().replace("Fragment", "");
        ;

        TypeSpec.Builder builder = TypeSpec.classBuilder(simpleName + "Builder")
                .addJavadoc("自动生成,请勿编辑!\n{@link " + ClassName.get((TypeElement) element) + "}")
                .addModifiers(Modifier.PUBLIC);

        List<FieldSpec> fields = createFields(element);
        for (FieldSpec field : fields) {
            builder.addField(field);
        }

        builder.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        builder.addMethod(MethodSpec.methodBuilder("builder")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addCode("return new $T();", className)
                .returns(className)
                .build());
        for (MethodSpec method : methods) {
            builder.addMethod(method);
        }
        JavaFile javaFile = JavaFile
                .builder(ClassName.get((TypeElement) element).packageName(), builder.build())
                // 设置表示缩进的字符串
                .indent("    ")
                .build();

        Path path = Paths.get(System.getProperty("user.dir"), "route", "src", "main", "java", "path");
        javaFile.writeTo(path);

        javaFile.writeTo(processingEnv.getFiler());
    }

    private TypeMirror getTypeMirror(String name) {
        return processingEnv.getElementUtils().getTypeElement(name).asType();
    }

    private boolean isFragment(Element element) {
        return types.isSubtype(element.asType(), getTypeMirror(Consts.FRAGMENT))
                || types.isSubtype(element.asType(), getTypeMirror(Consts.FRAGMENT_X))
                || types.isSubtype(element.asType(), getTypeMirror(Consts.FRAGMENT_V4));
    }
}
