package com.javarush.khmelov.util;

public class NanoSpring {

    private static final java.util.Map<Class<?>, Object> beans = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.List<Class<?>> beanDefinitions = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

    public static final String CLASSES = java.io.File.separator + "classes" + java.io.File.separator;
    public static final String EXT = ".class";
    public static final String DOT = ".";
    public static final String EMPTY = "";

    @SuppressWarnings("unchecked")
    public static <T> T find(Class<T> type) {
        try {
            if (beanDefinitions.isEmpty()) {
                init();
            }

            Object component = beans.get(type);
            if (component == null) {
                depth.set(depth.get() + 1);
                try {
                    java.lang.reflect.Constructor<?> constructor = type.getConstructors()[0];
                    Class<?>[] parameterTypes = constructor.getParameterTypes();
                    java.lang.reflect.Type[] genericParameterTypes = constructor.getGenericParameterTypes();
                    Object[] parameters = new Object[parameterTypes.length];

                    for (int i = 0; i < parameters.length; i++) {
                        Class<?> impl = findImpl(parameterTypes[i], genericParameterTypes[i]);
                        parameters[i] = find(impl);
                    }

                    Object newInstance = constructor.newInstance(parameters);
                    beans.putIfAbsent(type, newInstance);
                } finally {
                    depth.set(depth.get() - 1);
                }
            }

            if (depth.get() == 0) {
                injectFields();
            }

            return (T) beans.get(type);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void injectFields() {
        for (Object bean : beans.values()) {
            for (java.lang.reflect.Field field : bean.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                try {
                    if (field.get(bean) == null && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        Class<?> impl = findImpl(field.getType(), field.getGenericType());
                        field.set(bean, find(impl));
                    }
                } catch (Exception e) {
                    e.printStackTrace(System.err);
                }
            }
        }
    }

    private static synchronized void init() throws Exception {
        if (!beanDefinitions.isEmpty()) return;
        java.net.URL resource = NanoSpring.class.getProtectionDomain().getCodeSource().getLocation();
        java.nio.file.Path appRoot = java.nio.file.Path.of(resource.toURI());
        scanPackages(appRoot, "Controller", "Servlet", "Filter");
    }

    public static void scanPackages(java.nio.file.Path appPackage, String... excludes) {
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(appPackage)) {
            java.util.List<String> names = walk.map(java.nio.file.Path::toString)
                    .filter(o -> o.endsWith(EXT))
                    .filter(o -> java.util.Arrays.stream(excludes).noneMatch(o::contains))
                    .map(s -> s.substring(s.indexOf(CLASSES) + CLASSES.length()))
                    .map(s -> s.replace(EXT, EMPTY))
                    .map(s -> s.replace(java.io.File.separator, DOT))
                    .toList();
            for (String name : names) {
                beanDefinitions.add(Class.forName(name));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Class<?> findImpl(Class<?> aClass, java.lang.reflect.Type type) {
        for (Class<?> beanDefinition : beanDefinitions) {
            boolean assignable = aClass.isAssignableFrom(beanDefinition);
            boolean nonGeneric = beanDefinition.getTypeParameters().length == 0;
            boolean nonInterface = !beanDefinition.isInterface();
            boolean nonAbstract = !java.lang.reflect.Modifier.isAbstract(beanDefinition.getModifiers());
            boolean checkGenerics = checkGenerics(type, beanDefinition);
            if (assignable && nonGeneric && nonInterface && nonAbstract && checkGenerics) {
                return beanDefinition;
            }
        }
        throw new RuntimeException("Not found impl for " + aClass + " type=" + type);
    }

    private static boolean checkGenerics(java.lang.reflect.Type type, Class<?> impl) {
        java.util.List<? extends Class<?>> typeContractGeneric = getContractGeneric(type);
        return java.util.Objects.nonNull(impl) &&
               java.util.stream.Stream.<Class<?>>iterate(impl, java.util.Objects::nonNull, Class::getSuperclass)
                       .flatMap(c -> java.util.stream.Stream.concat(
                               java.util.stream.Stream.of(c.getGenericSuperclass()),
                               java.util.stream.Stream.of(c.getGenericInterfaces())))
                       .filter(java.util.Objects::nonNull)
                       .map(NanoSpring::getContractGeneric)
                       .anyMatch(typeContractGeneric::equals);
    }

    private static java.util.List<? extends Class<?>> getContractGeneric(java.lang.reflect.Type type) {
        String typeName = type.getTypeName();
        return !typeName.contains("<")
                ? java.util.List.of()
                : java.util.Arrays.stream(typeName
                        .replaceFirst(".+<", EMPTY)
                        .replace(">", EMPTY)
                        .split(","))
                .map(NanoSpring::getaClassOrNull)
                .toList();
    }

    private static Class<?> getaClassOrNull(String className) {
        try {
            return Class.forName(className.trim());
        } catch (Exception e) {
            return null;
        }
    }
}