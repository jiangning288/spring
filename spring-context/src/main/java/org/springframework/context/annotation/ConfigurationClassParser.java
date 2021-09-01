/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.context.annotation;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.parsing.Location;
import org.springframework.beans.factory.parsing.Problem;
import org.springframework.beans.factory.parsing.ProblemReporter;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;
import org.springframework.context.annotation.DeferredImportSelector.Group;
import org.springframework.core.NestedIOException;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.DefaultPropertySourceFactory;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertySourceFactory;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.StandardAnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

/**
 * Parses a {@link Configuration} class definition, populating a collection of
 * {@link ConfigurationClass} objects (parsing a single Configuration class may result in
 * any number of ConfigurationClass objects because one Configuration class may import
 * another using the {@link Import} annotation).
 *
 * <p>This class helps separate the concern of parsing the structure of a Configuration
 * class from the concern of registering BeanDefinition objects based on the content of
 * that model (with the exception of {@code @ComponentScan} annotations which need to be
 * registered immediately).
 *
 * <p>This ASM-based implementation avoids reflection and eager class loading in order to
 * interoperate effectively with lazy class loading in a Spring ApplicationContext.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Phillip Webb
 * @author Sam Brannen
 * @author Stephane Nicoll
 * @since 3.0
 * @see ConfigurationClassBeanDefinitionReader
 */
class ConfigurationClassParser {

	private static final PropertySourceFactory DEFAULT_PROPERTY_SOURCE_FACTORY = new DefaultPropertySourceFactory();

	private static final Comparator<DeferredImportSelectorHolder> DEFERRED_IMPORT_COMPARATOR =
			(o1, o2) -> AnnotationAwareOrderComparator.INSTANCE.compare(o1.getImportSelector(), o2.getImportSelector());


	private final Log logger = LogFactory.getLog(getClass());
	//构造器传入CachingMetadataReaderFactory
	private final MetadataReaderFactory metadataReaderFactory;
	//构造器传入FailFastProblemReporter
	private final ProblemReporter problemReporter;
	//容器传入
	private final Environment environment;
	//构造器传入DefaultResourceLoader
	private final ResourceLoader resourceLoader;
	//当前容器
	private final BeanDefinitionRegistry registry;
	//解析@ComponentScan
	private final ComponentScanAnnotationParser componentScanParser;
	//根据@Conditional注解指定的Condition接口来决定是否跳过此方法的解析
	private final ConditionEvaluator conditionEvaluator;
	//保存解析过的@Configuration类
	private final Map<ConfigurationClass, ConfigurationClass> configurationClasses = new LinkedHashMap<>();
	//保存被解析类父类，避免多个子类导致同一个父类被解析多次
	private final Map<String, ConfigurationClass> knownSuperclasses = new HashMap<>();
	//保存@PropertySource指定的资源
	private final List<String> propertySourceNames = new ArrayList<>();
	//保存@Import导入的类 key是正在解析的 value是被导入的
	private final ImportStack importStack = new ImportStack();
	//保存当@Import指定的是DeferredImportSelector，这类延迟解析 见parse方法
	private final DeferredImportSelectorHandler deferredImportSelectorHandler = new DeferredImportSelectorHandler();


	/**
	 * Create a new {@link ConfigurationClassParser} instance that will be used
	 * to populate the set of configuration classes.
	 */
	public ConfigurationClassParser(MetadataReaderFactory metadataReaderFactory,
			ProblemReporter problemReporter, Environment environment, ResourceLoader resourceLoader,
			BeanNameGenerator componentScanBeanNameGenerator, BeanDefinitionRegistry registry) {

		this.metadataReaderFactory = metadataReaderFactory;
		this.problemReporter = problemReporter;
		this.environment = environment;
		this.resourceLoader = resourceLoader;
		this.registry = registry;
		this.componentScanParser = new ComponentScanAnnotationParser(
				environment, resourceLoader, componentScanBeanNameGenerator, registry);
		this.conditionEvaluator = new ConditionEvaluator(registry, environment, resourceLoader);
	}


	public void parse(Set<BeanDefinitionHolder> configCandidates) {
		//5.1之前在这里实例化 this.deferredImportSelectors = new LinkedList<>();
		//5.1之后在DeferredImportSelectorHandler内部类里实例化
		//有关DeferredImportSelector和ImportSelector，后面重点讲解！！

		//根据BeanDefinition 的类型 做不同的处理,一般都会调用ConfigurationClassParser#parse 进行解析
		for (BeanDefinitionHolder holder : configCandidates) {
			BeanDefinition bd = holder.getBeanDefinition();
			try {
				//首次调用parse()方法处理的是AnnotatedBeanDefinition，通过@ImportResource加载的xml资源会走下面两个分支
				if (bd instanceof AnnotatedBeanDefinition) {
					//解析注解对象，并且把解析出来的bd放到map，但是这里的bd指的是普通的
					//何谓不普通的呢？比如@Bean 和各种beanFactoryPostProcessor得到的bean不在这里put
					//但是是这里解析，只是不put而已
					parse(((AnnotatedBeanDefinition) bd).getMetadata(), holder.getBeanName());
				}
				else if (bd instanceof AbstractBeanDefinition && ((AbstractBeanDefinition) bd).hasBeanClass()) {
					parse(((AbstractBeanDefinition) bd).getBeanClass(), holder.getBeanName());
				}
				else {
					parse(bd.getBeanClassName(), holder.getBeanName());
				}
			}
			catch (BeanDefinitionStoreException ex) {
				throw ex;
			}
			catch (Throwable ex) {
				throw new BeanDefinitionStoreException(
						"Failed to parse configuration class [" + bd.getBeanClassName() + "]", ex);
			}
		}
        // 等@configuration处理完，最后再处理DeferredImportSelector的实现类
		// 执行 this.deferredImportSelectorHandler.process();
		// 将deferredImportSelector导入的类放入ConfigurationClassParser内部类ImportStack的属性：private final MultiValueMap<String, AnnotationMetadata> imports
		// 为什么要延迟处理？因为比如springboot中有些自动装配类是有条件的@condition，就比如用户没有注入这个类型的bean才注入。
		this.deferredImportSelectorHandler.process();
	}

	protected final void parse(@Nullable String className, String beanName) throws IOException {
		Assert.notNull(className, "No bean class name for configuration class bean definition");
		MetadataReader reader = this.metadataReaderFactory.getMetadataReader(className);
		processConfigurationClass(new ConfigurationClass(reader, beanName));
	}

	protected final void parse(Class<?> clazz, String beanName) throws IOException {
		processConfigurationClass(new ConfigurationClass(clazz, beanName));
	}

	protected final void parse(AnnotationMetadata metadata, String beanName) throws IOException {
		processConfigurationClass(new ConfigurationClass(metadata, beanName));
	}

	/**
	 * Validate each {@link ConfigurationClass} object.
	 * @see ConfigurationClass#validate
	 */
	public void validate() {
		for (ConfigurationClass configClass : this.configurationClasses.keySet()) {
			configClass.validate(this.problemReporter);
		}
	}

	public Set<ConfigurationClass> getConfigurationClasses() {
		return this.configurationClasses.keySet();
	}


	protected void processConfigurationClass(ConfigurationClass configClass) throws IOException {
		//根据@Conditional指定的Condition实现类match方法返回值决定是否跳过
		if (this.conditionEvaluator.shouldSkip(configClass.getMetadata(), ConfigurationPhase.PARSE_CONFIGURATION)) {
			return;
		}
		// 处理imported的情况
		// 就是当前这个注解类有没有被别的类import
		// 如果本次解析的configClass早已解析过了(class name一样就属于一个configClass),
		// 并且都是被导入的，则合并导入configClass的ConfigurationClas
		ConfigurationClass existingClass = this.configurationClasses.get(configClass);
		if (existingClass != null) {
			if (configClass.isImported()) {
				if (existingClass.isImported()) {
					existingClass.mergeImportedBy(configClass);
				}
				// Otherwise ignore new imported config class; existing non-imported class overrides it.
				return;
			}
			else {
				// Explicit bean definition found, probably replacing an import.
				// Let's remove the old one and go with the new one.
				//如果用户手动定义了一个ConfigurationClass,则去掉可能之前被自动@Import的类
				this.configurationClasses.remove(configClass);
				this.knownSuperclasses.values().removeIf(configClass::equals);
			}
		}

		// Recursively process the configuration class and its superclass hierarchy.
		// 递归处理配置类及其超类层次结构
		SourceClass sourceClass = asSourceClass(configClass);
		do {
			//从配置类解析一切可能的bean形式，内部类，成员方法，@Import等，返回值为父类，
			//继续解析父类直到为null
			sourceClass = doProcessConfigurationClass(configClass, sourceClass);
		}
		while (sourceClass != null);
		//一个map，用来存放待注册出来的bean（比如importselector，import普通类）（注意这里的bean不是对象，仅仅bean的信息，因为还没到实例化这一步）
		this.configurationClasses.put(configClass, configClass);
	}

	/**
	 * Apply processing and build a complete {@link ConfigurationClass} by reading the
	 * annotations, members and methods from the source class. This method can be called
	 * multiple times as relevant sources are discovered.
	 * @param configClass the configuration class being build
	 * @param sourceClass a source class
	 * @return the superclass, or {@code null} if none found or previously processed
	 */
	@Nullable
	protected final SourceClass doProcessConfigurationClass(ConfigurationClass configClass, SourceClass sourceClass)
			throws IOException {

		if (configClass.getMetadata().isAnnotated(Component.class.getName())) {
			// Recursively process any member (nested) classes first
			//处理内部类
			processMemberClasses(configClass, sourceClass);
		}

		// Process any @PropertySource annotations
		// 处理@PropertySources注解和@PropertySource注解，交给processPropertySource去解析
		// 必须是ConfigurableEnvironment的环境采取解析，否则发出警告：会忽略这个不进行解析
		for (AnnotationAttributes propertySource : AnnotationConfigUtils.attributesForRepeatable(
				sourceClass.getMetadata(), PropertySources.class,
				org.springframework.context.annotation.PropertySource.class)) {
			if (this.environment instanceof ConfigurableEnvironment) {
				//将@PropertySource指定的properties资源合并到environment中，注意如果有多个@PropertySource命名相同name属性，
				//则会合并相同名字的资源文件.并且填充configClass的propertySourceNames属性
				processPropertySource(propertySource);
			}
			else {
				logger.info("Ignoring @PropertySource annotation on [" + sourceClass.getMetadata().getClassName() +
						"]. Reason: Environment must implement ConfigurableEnvironment");
			}
		}

		// Process any @ComponentScan annotations
		// 处理@ComponentScan注解
		Set<AnnotationAttributes> componentScans = AnnotationConfigUtils.attributesForRepeatable(
				sourceClass.getMetadata(), ComponentScans.class, ComponentScan.class);
		if (!componentScans.isEmpty() &&
				!this.conditionEvaluator.shouldSkip(sourceClass.getMetadata(), ConfigurationPhase.REGISTER_BEAN)) {
			for (AnnotationAttributes componentScan : componentScans) {
				// The config class is annotated with @ComponentScan -> perform the scan immediately
				//扫描普通类=componentScan
				//这里扫描出来所有@Component
				//并且把扫描的出来的普通bean放到map当中
				Set<BeanDefinitionHolder> scannedBeanDefinitions =
						this.componentScanParser.parse(componentScan, sourceClass.getMetadata().getClassName());
				// Check the set of scanned definitions for any further config classes and parse recursively if needed
				// 检查扫描出来的类当中是否还有configuration
				for (BeanDefinitionHolder holder : scannedBeanDefinitions) {
					BeanDefinition bdCand = holder.getBeanDefinition().getOriginatingBeanDefinition();
					if (bdCand == null) {
						bdCand = holder.getBeanDefinition();
					}
					//再次判断是Full还是lite，然后重复之前的步骤
					if (ConfigurationClassUtils.checkConfigurationClassCandidate(bdCand, this.metadataReaderFactory)) {
						parse(bdCand.getBeanClassName(), holder.getBeanName());
					}
				}
			}
		}

		/**
		 * 上面的代码就是扫描普通类----@Component，并且放到了map当中
		 * -------------------------------------------------
		 */

		// Process any @Import annotations
		//【重要！！！！！】处理@Import注解的4种情况(3种类型）
		//在@Import注解的参数中可以填写类名，例如@Import(Abc.class)，根据类Abc的不同类型，spring容器有以下四种处理方式：
		//1. 如果Abc类实现了ImportSelector接口，spring容器就会实例化Abc类，并且调用其selectImports方法；
		//2. DeferredImportSelector是ImportSelector的子类，如果Abc类实现了DeferredImportSelector接口，spring容器就会实例化Abc类，
		//   并且调用其selectImports方法，和ImportSelector的实例不同的是，DeferredImportSelector的实例的selectImports方法调用时机
		//   晚于ImportSelector的实例，要等到@Configuration注解中相关的业务全部都处理完了才会调用
		//   （具体逻辑在ConfigurationClassParser.processDeferredImportSelectors方法中）。
		//3. 如果Abc类实现了ImportBeanDefinitionRegistrar接口，spring容器就会实例化Abc类，并且调用其registerBeanDefinitions方法；
		//4. 如果Abc没有实现ImportSelector、DeferredImportSelector、ImportBeanDefinitionRegistrar等其中的任何一个，spring容器就会实例化Abc类。
		/**
		 * 这里处理的import是需要判断我们的类当中时候有@Import注解
		 * 如果有这把@Import当中的值拿出来，是一个类
		 * 比如@Import(xxxxx.class)，那么这里便把xxxxx传进去进行解析
		 * 在解析的过程中如果发觉是一个importSelector那么就回调selector的方法
		 * 返回一个字符串（类名），通过这个字符串得到一个类
		 * 继而在递归调用本方法来处理这个类
		 */
		//注意，这里的第三个参数：getImports(sourceClass)，获取的是config上面的@import注解里的class，
		//而里面递归调用传进来的第三个参数是@import注解里的class返回的那个bean，即判断那个被返回的bean是不是实现了以上的import接口。
		processImports(configClass, sourceClass, getImports(sourceClass), true);

		// Process any @ImportResource annotations
		// 处理@ImportResource注解
		// 交给environment.resolveRequiredPlaceholders(resource)处理
		AnnotationAttributes importResource =
				AnnotationConfigUtils.attributesFor(sourceClass.getMetadata(), ImportResource.class);
		if (importResource != null) {
			String[] resources = importResource.getStringArray("locations");
			Class<? extends BeanDefinitionReader> readerClass = importResource.getClass("reader");
			for (String resource : resources) {
				String resolvedResource = this.environment.resolveRequiredPlaceholders(resource);
				//解析导入的文件放入ConfigClass的importedResources集合中
				configClass.addImportedResource(resolvedResource, readerClass);
			}
		}

		// Process individual @Bean methods
		// 处理@Bean方法
		// 【重点！！】遵照方法的先后声明顺序！！
		// @Bean方法执行顺序和有无static没有半毛钱关系！
		// clazz.getDeclaredMethods()得到的是Method[]数组，是有序的。这个顺序由字节码（定义顺序）来保证：先定义，先服务。！
		// static并不是真正意义上的提高Bean优先级，可以使用@DependsOn注解来保证，它也是和Bean顺序息息相关的一个注解，后面再说
		// 所以关于@Bean方法的执行顺序的正确结论应该是：
		//     在同一配置类内，在无其它“干扰”情况下（无@DependsOn、@Lazy等注解），
		//     @Bean方法的执行顺序遵从的是定义顺序（后置处理器类型除外）。
		//     对于普通类型（非后置处理器类型）的@Bean方法，使用static关键字并不能改变顺序（按照方法定义顺序执行）
		//     static关键字一般有且仅用于@Bean方法返回为BeanPostProcessor、BeanFactoryPostProcessor等类型的方法，并且建议此种方法请务必使用static修饰，否则容易导致隐患，埋雷
		//     在同一配置类内，与其说它是提升了Bean的优先级，倒不如说它让@Bean方法静态化从而不再需要依赖所在类的实例即可独立运行
		Set<MethodMetadata> beanMethods = retrieveBeanMethodMetadata(sourceClass);
		for (MethodMetadata methodMetadata : beanMethods) {
			// 检索添加@Bean的方法，添加到ConfigClass中的beanMethods的set集合中
			configClass.addBeanMethod(new BeanMethod(methodMetadata, configClass));
		}

		// Process default methods on interfaces
		// 处理接口的默认方法（jdk1.8）
		// 这个特别有意思：处理接口中被@Bean注解默认方法,代码如下
		// 因为JDK8以后接口可以写default方法了，所以接口竟然也能给容器里注册Bean了
		// 但是需要注意：这里的意思并不是说你写个接口然后标注上@Configuration注解，然后@Bean注入就可以了
		// 这个解析的意思是我们的配置类可以实现接口，然后在所实现的接口里面若有@Bean的注解默认方法，是会加入到容器的
		processInterfaces(configClass, sourceClass);

		// Process superclass, if any
		// 如果有父类的话,则返回父类进行进一步的解析,否则返回null
		// 如果当前SourceClass还有父类，将父类走一遍上述流程。
		// @EnableWebMvc中的DelegatingWebMvcConfiguration就是这么玩的
		// 它自己标注了@Configuration注解，但是真正@Bean注入，都是它父类去干的

		if (sourceClass.getMetadata().hasSuperClass()) {
			String superclass = sourceClass.getMetadata().getSuperClassName();
			if (superclass != null && !superclass.startsWith("java") &&
					!this.knownSuperclasses.containsKey(superclass)) {
				this.knownSuperclasses.put(superclass, configClass);
				// Superclass found, return its annotation metadata and recurse
				// 若找到了父类，会返回然后继续处理
				return sourceClass.getSuperClass();
			}
		}

		// No superclass -> processing is complete
		// 没有父类，就停止了，处理结束
		return null;
	}

	/**
	 * Register member (nested) classes that happen to be configuration classes themselves.
	 */
	//注册配置类里的成员类
	private void processMemberClasses(ConfigurationClass configClass, SourceClass sourceClass) throws IOException {
		Collection<SourceClass> memberClasses = sourceClass.getMemberClasses();
		if (!memberClasses.isEmpty()) {
			List<SourceClass> candidates = new ArrayList<>(memberClasses.size());
			for (SourceClass memberClass : memberClasses) {
				if (ConfigurationClassUtils.isConfigurationCandidate(memberClass.getMetadata()) &&
						!memberClass.getMetadata().getClassName().equals(configClass.getMetadata().getClassName())) {
					candidates.add(memberClass);
				}
			}
			//@Order注解排序
			OrderComparator.sort(candidates);
			for (SourceClass candidate : candidates) {
				//避免循环导入
				if (this.importStack.contains(configClass)) {
					//同一个类被多次导入抛BeanDefinitionParsingException
					this.problemReporter.error(new CircularImportProblem(configClass, this.importStack));
				}
				else {
					this.importStack.push(configClass);
					try {
						//可以看到对一个配置类的解析是先深度后广度
						processConfigurationClass(candidate.asConfigClass(configClass));
					}
					finally {
						this.importStack.pop();
					}
				}
			}
		}
	}

	/**
	 * Register default methods on interfaces implemented by the configuration class.
	 */
	private void processInterfaces(ConfigurationClass configClass, SourceClass sourceClass) throws IOException {
		for (SourceClass ifc : sourceClass.getInterfaces()) {
			Set<MethodMetadata> beanMethods = retrieveBeanMethodMetadata(ifc);
			for (MethodMetadata methodMetadata : beanMethods) {
				if (!methodMetadata.isAbstract()) {
					// A default method or other concrete method on a Java 8+ interface...
					configClass.addBeanMethod(new BeanMethod(methodMetadata, configClass));
				}
			}
			processInterfaces(configClass, ifc);
		}
	}

	/**
	 * Retrieve the metadata for all <code>@Bean</code> methods.
	 */
	//检索当前SourceClass里所有@Bean方法
	private Set<MethodMetadata> retrieveBeanMethodMetadata(SourceClass sourceClass) {
		AnnotationMetadata original = sourceClass.getMetadata();
		Set<MethodMetadata> beanMethods = original.getAnnotatedMethods(Bean.class.getName());
		//超过一个被@Bean的方法才需要排序，先后声明顺序
		if (beanMethods.size() > 1 && original instanceof StandardAnnotationMetadata) {
			// 这里注释是说由于jvm返回的方法列表顺序不能保证，
			// 这里尝试使用asm字节码技术拿到方法在类中的声明顺序，以此来为这些被@Bean修饰的方法排序
			// Try reading the class file via ASM for deterministic declaration order...
			// Unfortunately, the JVM's standard reflection returns methods in arbitrary
			// order, even between different runs of the same application on the same JVM.
			try {
				AnnotationMetadata asm =
						this.metadataReaderFactory.getMetadataReader(original.getClassName()).getAnnotationMetadata();
				Set<MethodMetadata> asmMethods = asm.getAnnotatedMethods(Bean.class.getName());
				if (asmMethods.size() >= beanMethods.size()) {
					//注意，这里生成的是一个LinkedHashSet
					Set<MethodMetadata> selectedMethods = new LinkedHashSet<>(asmMethods.size());
					for (MethodMetadata asmMethod : asmMethods) {
						for (MethodMetadata beanMethod : beanMethods) {
							if (beanMethod.getMethodName().equals(asmMethod.getMethodName())) {
								selectedMethods.add(beanMethod);
								break;
							}
						}
					}
					if (selectedMethods.size() == beanMethods.size()) {
						// All reflection-detected methods found in ASM method set -> proceed
						beanMethods = selectedMethods;
					}
				}
			}
			catch (IOException ex) {
				logger.debug("Failed to read class file via ASM for determining @Bean method order", ex);
				// No worries, let's continue with the reflection metadata we started with...
			}
		}
		return beanMethods;
	}


	/**
	 * Process the given <code>@PropertySource</code> annotation metadata.
	 * @param propertySource metadata for the <code>@PropertySource</code> annotation found
	 * @throws IOException if loading a property source failed
	 */
	private void processPropertySource(AnnotationAttributes propertySource) throws IOException {
		String name = propertySource.getString("name");
		if (!StringUtils.hasLength(name)) {
			name = null;
		}
		String encoding = propertySource.getString("encoding");
		if (!StringUtils.hasLength(encoding)) {
			encoding = null;
		}
		String[] locations = propertySource.getStringArray("value");
		Assert.isTrue(locations.length > 0, "At least one @PropertySource(value) location is required");
		boolean ignoreResourceNotFound = propertySource.getBoolean("ignoreResourceNotFound");

		Class<? extends PropertySourceFactory> factoryClass = propertySource.getClass("factory");
		PropertySourceFactory factory = (factoryClass == PropertySourceFactory.class ?
				DEFAULT_PROPERTY_SOURCE_FACTORY : BeanUtils.instantiateClass(factoryClass));

		for (String location : locations) {
			try {
				String resolvedLocation = this.environment.resolveRequiredPlaceholders(location);
				Resource resource = this.resourceLoader.getResource(resolvedLocation);
				//添加PropertySource
				addPropertySource(factory.createPropertySource(name, new EncodedResource(resource, encoding)));
			}
			catch (IllegalArgumentException | FileNotFoundException | UnknownHostException ex) {
				// Placeholders not resolvable or resource not found when trying to open it
				if (ignoreResourceNotFound) {
					if (logger.isInfoEnabled()) {
						logger.info("Properties location [" + location + "] not resolvable: " + ex.getMessage());
					}
				}
				else {
					throw ex;
				}
			}
		}
	}

	private void addPropertySource(PropertySource<?> propertySource) {
		String name = propertySource.getName();
		MutablePropertySources propertySources = ((ConfigurableEnvironment) this.environment).getPropertySources();

		if (this.propertySourceNames.contains(name)) {
			// We've already added a version, we need to extend it
			PropertySource<?> existing = propertySources.get(name);
			if (existing != null) {
				PropertySource<?> newSource = (propertySource instanceof ResourcePropertySource ?
						((ResourcePropertySource) propertySource).withResourceName() : propertySource);
				if (existing instanceof CompositePropertySource) {
					((CompositePropertySource) existing).addFirstPropertySource(newSource);
				}
				else {
					if (existing instanceof ResourcePropertySource) {
						existing = ((ResourcePropertySource) existing).withResourceName();
					}
					CompositePropertySource composite = new CompositePropertySource(name);
					composite.addPropertySource(newSource);
					composite.addPropertySource(existing);
					propertySources.replace(name, composite);
				}
				return;
			}
		}

		if (this.propertySourceNames.isEmpty()) {
			//第一个添加进来的在最后
			propertySources.addLast(propertySource);
		}
		else {
			String firstProcessed = this.propertySourceNames.get(this.propertySourceNames.size() - 1);
			propertySources.addBefore(firstProcessed, propertySource);
		}
		this.propertySourceNames.add(name);
	}


	/**
	 * Returns {@code @Import} class, considering all meta-annotations.
	 */
	private Set<SourceClass> getImports(SourceClass sourceClass) throws IOException {
		Set<SourceClass> imports = new LinkedHashSet<>();
		Set<SourceClass> visited = new LinkedHashSet<>();
		collectImports(sourceClass, imports, visited);
		return imports;
	}

	/**
	 * Recursively collect all declared {@code @Import} values. Unlike most
	 * meta-annotations it is valid to have several {@code @Import}s declared with
	 * different values; the usual process of returning values from the first
	 * meta-annotation on a class is not sufficient.
	 * <p>For example, it is common for a {@code @Configuration} class to declare direct
	 * {@code @Import}s in addition to meta-imports originating from an {@code @Enable}
	 * annotation.
	 * @param sourceClass the class to search
	 * @param imports the imports collected so far
	 * @param visited used to track visited classes to prevent infinite recursion
	 * @throws IOException if there is any problem reading metadata from the named class
	 */
	private void collectImports(SourceClass sourceClass, Set<SourceClass> imports, Set<SourceClass> visited)
			throws IOException {

		if (visited.add(sourceClass)) {
			for (SourceClass annotation : sourceClass.getAnnotations()) {
				String annName = annotation.getMetadata().getClassName();
				if (!annName.startsWith("java") && !annName.equals(Import.class.getName())) {
					collectImports(annotation, imports, visited);
				}
			}
			imports.addAll(sourceClass.getAnnotationAttributes(Import.class.getName(), "value"));
		}
	}

	/**
	 * 处理配置类上的@Import注解引入的类
	 *
	 * @param configClass 配置类，(例如Config类）
	 * @param currentSourceClass 当前资源类
	 * @param importCandidates 该配置类中的@Import注解导入的候选类列表
	 * @param checkForCircularImports 是否循环检查导入
	 */
	private void processImports(ConfigurationClass configClass, SourceClass currentSourceClass,
			Collection<SourceClass> importCandidates, boolean checkForCircularImports) {
		// 如果该@Import注解导入的列表为空，直接返回
		// 注：获取@Import是递归获取，任意子类父类上标注有都行的
		if (importCandidates.isEmpty()) {
			return;
		}
        // 循环检查导入
		// 如果存在循环依赖的话,则直接抛出异常(比如你@Import我，我@Import你这种情况)
		if (checkForCircularImports && isChainedImportOnStack(configClass)) {
			this.problemReporter.error(new CircularImportProblem(configClass, this.importStack));
		}
		else {
			this.importStack.push(configClass);
			try {
				// 依次处理每个@Import里面候选的Bean们
				for (SourceClass candidate : importCandidates) {
					if (candidate.isAssignable(ImportSelector.class)) {
						// Candidate class is an ImportSelector -> delegate to it to determine imports
						//加载实现ImportSelector接口的这个类
						Class<?> candidateClass = candidate.loadClass();
						//实例化实现ImportSelector接口的这个类
						ImportSelector selector = BeanUtils.instantiateClass(candidateClass, ImportSelector.class);
						//检查是否实现了EnvironmentAware、BeanFactoryAware、BeanClassLoaderAware、ResourceLoaderAware等接口，如果实现了就调用对应的方法
						ParserStrategyUtils.invokeAwareMethods(
								selector, this.environment, this.resourceLoader, this.registry);
						//如果为延迟导入处理则加入集合当中
						if (selector instanceof DeferredImportSelector) {
							this.deferredImportSelectorHandler.handle(
									configClass, (DeferredImportSelector) selector);
						}
						else {
							//回调selectImports方法
							String[] importClassNames = selector.selectImports(currentSourceClass.getMetadata());
							//将String中的类名转化为SourceClass
							Collection<SourceClass> importSourceClasses = asSourceClasses(importClassNames);
							//递归处理每一个selectImports方法返回的类，判断这个返回的类有没有实现import接口，即返回类本身是不是一个import类
							//因为我们这里放进去的Bean，有可能是普通Bean，当然也还有可能是实现了ImportSelector等等接口的，因此此处继续调用processImports进行递归处理
							processImports(configClass, currentSourceClass, importSourceClasses, false);
						}
					}
					else if (candidate.isAssignable(ImportBeanDefinitionRegistrar.class)) {
						// Candidate class is an ImportBeanDefinitionRegistrar ->
						// delegate to it to register additional bean definitions
						Class<?> candidateClass = candidate.loadClass();
						//实例化
						ImportBeanDefinitionRegistrar registrar =
								BeanUtils.instantiateClass(candidateClass, ImportBeanDefinitionRegistrar.class);
						//检查
						ParserStrategyUtils.invokeAwareMethods(
								registrar, this.environment, this.resourceLoader, this.registry);
						//调用configClass.addImportBeanDefinitionRegistrar方法
						//将ImportBeanDefinitionRegistrar实现类存入configClass的成员变量importBeanDefinitionRegistrars中，
						//后面的ConfigurationClassPostProcessor类的processConfigBeanDefinitions方法中，
						//this.reader.loadBeanDefinitions(configClasses);
						//会调用这些ImportBeanDefinitionRegistrar实现类的registerBeanDefinitions方法：
						configClass.addImportBeanDefinitionRegistrar(registrar, currentSourceClass.getMetadata());
					}
					else {
						// Candidate class not an ImportSelector or ImportBeanDefinitionRegistrar ->
						// process it as an @Configuration class
						// 如果当前的类既不是ImportSelector也不是ImportBeanDefinitionRegistar就进行@Configuration的解析处理
						// （其它类统一按照@Configuration类来处理，所以加不加@Configuration注解都能被导入）
						// 加入到importStack后调用processConfigurationClass 进行处理
						this.importStack.registerImport(
								currentSourceClass.getMetadata(), candidate.getMetadata().getClassName());
						//processConfigurationClass里面主要就是把类放到configurationClasses
						//configurationClasses是一个集合，会在后面拿出来解析成bd继而注册
						//可以看到普通类在扫描出来的时候就被注册了
						//如果是importSelector，会先放到configurationClasses后面进行出来注册
						processConfigurationClass(candidate.asConfigClass(configClass));
					}
				}
			}
			catch (BeanDefinitionStoreException ex) {
				throw ex;
			}
			catch (Throwable ex) {
				throw new BeanDefinitionStoreException(
						"Failed to process import candidates for configuration class [" +
						configClass.getMetadata().getClassName() + "]", ex);
			}
			finally {
				this.importStack.pop();
			}
		}
	}

	private boolean isChainedImportOnStack(ConfigurationClass configClass) {
		if (this.importStack.contains(configClass)) {
			String configClassName = configClass.getMetadata().getClassName();
			AnnotationMetadata importingClass = this.importStack.getImportingClassFor(configClassName);
			while (importingClass != null) {
				if (configClassName.equals(importingClass.getClassName())) {
					return true;
				}
				importingClass = this.importStack.getImportingClassFor(importingClass.getClassName());
			}
		}
		return false;
	}

	ImportRegistry getImportRegistry() {
		return this.importStack;
	}


	/**
	 * Factory method to obtain a {@link SourceClass} from a {@link ConfigurationClass}.
	 */
	private SourceClass asSourceClass(ConfigurationClass configurationClass) throws IOException {
		AnnotationMetadata metadata = configurationClass.getMetadata();
		if (metadata instanceof StandardAnnotationMetadata) {
			return asSourceClass(((StandardAnnotationMetadata) metadata).getIntrospectedClass());
		}
		return asSourceClass(metadata.getClassName());
	}

	/**
	 * Factory method to obtain a {@link SourceClass} from a {@link Class}.
	 */
	SourceClass asSourceClass(@Nullable Class<?> classType) throws IOException {
		if (classType == null) {
			return new SourceClass(Object.class);
		}
		try {
			// Sanity test that we can reflectively read annotations,
			// including Class attributes; if not -> fall back to ASM
			for (Annotation ann : classType.getAnnotations()) {
				AnnotationUtils.validateAnnotation(ann);
			}
			return new SourceClass(classType);
		}
		catch (Throwable ex) {
			// Enforce ASM via class name resolution
			return asSourceClass(classType.getName());
		}
	}

	/**
	 * Factory method to obtain {@link SourceClass SourceClasss} from class names.
	 */
	private Collection<SourceClass> asSourceClasses(String... classNames) throws IOException {
		List<SourceClass> annotatedClasses = new ArrayList<>(classNames.length);
		for (String className : classNames) {
			annotatedClasses.add(asSourceClass(className));
		}
		return annotatedClasses;
	}

	/**
	 * Factory method to obtain a {@link SourceClass} from a class name.
	 */
	SourceClass asSourceClass(@Nullable String className) throws IOException {
		if (className == null) {
			return new SourceClass(Object.class);
		}
		if (className.startsWith("java")) {
			// Never use ASM for core java types
			try {
				return new SourceClass(ClassUtils.forName(className, this.resourceLoader.getClassLoader()));
			}
			catch (ClassNotFoundException ex) {
				throw new NestedIOException("Failed to load class [" + className + "]", ex);
			}
		}
		return new SourceClass(this.metadataReaderFactory.getMetadataReader(className));
	}


	@SuppressWarnings("serial")
	//内部类
	private static class ImportStack extends ArrayDeque<ConfigurationClass> implements ImportRegistry {
		//ImportSelector 和 DeferredImportSelector 导入的类，全部放到ConfigurationClassParser内部类ImportStack的属性：
		private final MultiValueMap<String, AnnotationMetadata> imports = new LinkedMultiValueMap<>();

		public void registerImport(AnnotationMetadata importingClass, String importedClass) {
			this.imports.add(importedClass, importingClass);
		}

		@Override
		@Nullable
		public AnnotationMetadata getImportingClassFor(String importedClass) {
			return CollectionUtils.lastElement(this.imports.get(importedClass));
		}

		@Override
		public void removeImportingClass(String importingClass) {
			for (List<AnnotationMetadata> list : this.imports.values()) {
				for (Iterator<AnnotationMetadata> iterator = list.iterator(); iterator.hasNext();) {
					if (iterator.next().getClassName().equals(importingClass)) {
						iterator.remove();
						break;
					}
				}
			}
		}

		/**
		 * Given a stack containing (in order)
		 * <ul>
		 * <li>com.acme.Foo</li>
		 * <li>com.acme.Bar</li>
		 * <li>com.acme.Baz</li>
		 * </ul>
		 * return "[Foo->Bar->Baz]".
		 */
		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder("[");
			Iterator<ConfigurationClass> iterator = iterator();
			while (iterator.hasNext()) {
				builder.append(iterator.next().getSimpleName());
				if (iterator.hasNext()) {
					builder.append("->");
				}
			}
			return builder.append(']').toString();
		}
	}


	private class DeferredImportSelectorHandler {

		// 但是放入时机不一样，DeferredImportSelector 首次导入时，
		// 存放到ConfigurationClassParser内部类DeferredImportSelectorHandler中的属性
		// List<DeferredImportSelectorHolder> deferredImportSelectors
		// org.springframework.context.annotation.ConfigurationClassParser.processImports
		// 在这个方法中会把deferredImportSelectors加载进来
		@Nullable
		private List<DeferredImportSelectorHolder> deferredImportSelectors = new ArrayList<>();

		/**
		 * Handle the specified {@link DeferredImportSelector}. If deferred import
		 * selectors are being collected, this registers this instance to the list. If
		 * they are being processed, the {@link DeferredImportSelector} is also processed
		 * immediately according to its {@link DeferredImportSelector.Group}.
		 * @param configClass the source configuration class
		 * @param importSelector the selector to handle
		 */
		//DeferredImportSelector 的预处理
		public void handle(ConfigurationClass configClass, DeferredImportSelector importSelector) {
			// 将 DeferredImportSelector  和其引入的配置类保存起来。
			DeferredImportSelectorHolder holder = new DeferredImportSelectorHolder(
					configClass, importSelector);
			// 如果deferredImportSelectors 为空，则重新注册
			if (this.deferredImportSelectors == null) {
				DeferredImportSelectorGroupingHandler handler = new DeferredImportSelectorGroupingHandler();
				handler.register(holder);
				handler.processGroupImports();
			}
			else {
				// 将当前的 config 和 Selector 的持有者保存起来
				this.deferredImportSelectors.add(holder);
			}
		}

		public void process() {
			// 获取待处理的 DeferredImportSelectorHolder
			List<DeferredImportSelectorHolder> deferredImports = this.deferredImportSelectors;
			this.deferredImportSelectors = null;
			try {
				if (deferredImports != null) {
					//创建DeferredImportSelectorGroupingHandler对象
					DeferredImportSelectorGroupingHandler handler = new DeferredImportSelectorGroupingHandler();
					//根据@Order进行排序
					deferredImports.sort(DEFERRED_IMPORT_COMPARATOR);
					//遍历调用DeferredImportSelectorGroupingHandler的register方法，将DeferredImportSelectorHolder数据set到DeferredImportSelectorGroupingHandler内
					//根据不同的group 进行分组注册
					deferredImports.forEach(handler::register);
					//按照分组调用handler的processGroupImports方法进行解析注册
					handler.processGroupImports();
				}
			}
			finally {
				//置空
				this.deferredImportSelectors = new ArrayList<>();
			}
		}

	}


	private class DeferredImportSelectorGroupingHandler {
		//有两个字段，通过register方法挨个进行register（相当于set方法）到上两个字段中， 最后统一调用processGroupImports方法进行处理。

		//group中存储的key为DeferredImportSelector.Group或者DeferredImportSelectorHolder对象（因为是调用DeferredImportSelector的getImportGroup方法获取的，可能为null，所以key为Object类型）。value为DeferredImportSelectorGrouping类型
		private final Map<Object, DeferredImportSelectorGrouping> groupings = new LinkedHashMap<>();

		//configurationClasses存放的是注解信息和标记注解的类
		private final Map<AnnotationMetadata, ConfigurationClass> configurationClasses = new HashMap<>();

		//这里有两步操作：
		//1. 将不同的 DeferredImportSelectorHolder 按照分组进行划分
		//2. 将DeferredImportSelectorHolder 的信息保存到 configurationClasses 中。(供后面调用的时候获取)
		public void register(DeferredImportSelectorHolder deferredImport) {
			// 调用DeferredImportSelector的getImportGroup方法, 返回一个继承Group接口类的class，获取当前 DeferredImportSelector的Group
			Class<? extends Group> group = deferredImport.getImportSelector()
					.getImportGroup();
			//开始以group做为key进行分组
            //1. 其中 createGroup(group) 就是创建了上面的 group 对象，如果为空，则创建一个默认的组对象 DefaultDeferredImportSelectorGroup。
            //2. 这个方法的意思是，如果 map 中没有这个元素则用后面的方法创建，如果有则直接取出来

			DeferredImportSelectorGrouping grouping = this.groupings.computeIfAbsent(
					(group != null ? group : deferredImport),
					key -> new DeferredImportSelectorGrouping(createGroup(group)));
			// 将当前 DeferredImportSelector 添加到同一分组中的
			grouping.add(deferredImport);
			// 保存需要处理的配置类，将导入DeferredImportSelector的配置类做为key, 放入处理器的configurationClasses变量中
			this.configurationClasses.put(deferredImport.getConfigurationClass().getMetadata(),
					deferredImport.getConfigurationClass());
		}


		// DeferredImportSelector 的处理过程并非是直接 调用ImportSelector#selectImports方法。
		// 而是调用 DeferredImportSelector.Group#process 和 Group#selectImports 方法来完成引入功能。
		public void processGroupImports() {
			//获取并循环所有的分组
			for (DeferredImportSelectorGrouping grouping : this.groupings.values()) {
				//getImports方法调用group类的process和selectImports方法
				//selectImports方法返回的迭代器, 调用迭代器的forEach方法, 循环注册封装的Entry对象进spring容器
				//必须返回迭代器, 这里直接调用forEach方法, 否则会空指针
				grouping.getImports().forEach(entry -> {
					//register方法最后将所有DeferredImportSelector的导入配置类都放入configurationClasses中
					//所以创建Entry对象的metadata变量必须是所有导入的配置类其中之一, 还不能new出来, 否则configurationClass 为空, 后面代码会报错
					ConfigurationClass configurationClass = this.configurationClasses.get(
							entry.getMetadata());
					try {
						//处理Import类方法
						// entry.getImportClassName() 获取到了引入的类
						processImports(configurationClass, asSourceClass(configurationClass),
								asSourceClasses(entry.getImportClassName()), false);
					}
					catch (BeanDefinitionStoreException ex) {
						throw ex;
					}
					catch (Throwable ex) {
						throw new BeanDefinitionStoreException(
								"Failed to process import candidates for configuration class [" +
										configurationClass.getMetadata().getClassName() + "]", ex);
					}
				});
			}
		}

		private Group createGroup(@Nullable Class<? extends Group> type) {
			Class<? extends Group> effectiveType = (type != null ? type
					: DefaultDeferredImportSelectorGroup.class);
			Group group = BeanUtils.instantiateClass(effectiveType);
			ParserStrategyUtils.invokeAwareMethods(group,
					ConfigurationClassParser.this.environment,
					ConfigurationClassParser.this.resourceLoader,
					ConfigurationClassParser.this.registry);
			return group;
		}

	}


	private static class DeferredImportSelectorHolder {

		private final ConfigurationClass configurationClass;
		//存放属于这个组的importseletor
		private final DeferredImportSelector importSelector;

		public DeferredImportSelectorHolder(ConfigurationClass configClass, DeferredImportSelector selector) {
			this.configurationClass = configClass;
			this.importSelector = selector;
		}

		public ConfigurationClass getConfigurationClass() {
			return this.configurationClass;
		}

		public DeferredImportSelector getImportSelector() {
			return this.importSelector;
		}
	}


	private static class DeferredImportSelectorGrouping {

		private final DeferredImportSelector.Group group;

		private final List<DeferredImportSelectorHolder> deferredImports = new ArrayList<>();

		DeferredImportSelectorGrouping(Group group) {
			this.group = group;
		}

		public void add(DeferredImportSelectorHolder deferredImport) {
			this.deferredImports.add(deferredImport);
		}

		/**
		 * Return the imports defined by the group.
		 * @return each import with its associated configuration class
		 */
		public Iterable<Group.Entry> getImports() {
			//拿出所有属于该组的DeferredImportSelector, 循环调用DeferredImportSelector的用户代码process方法
			for (DeferredImportSelectorHolder deferredImport : this.deferredImports) {
				//将DeferredImportSelector的导入配置类的Metadata和他本身作为参数传入
				// 调用 DeferredImportSelector.Group#process
				this.group.process(deferredImport.getConfigurationClass().getMetadata(),
						deferredImport.getImportSelector());
			}
			//调用Group类的selectImports返回迭代器
			// 调用 DeferredImportSelector.Group#selectImports
			return this.group.selectImports();
		}
	}


	private static class DefaultDeferredImportSelectorGroup implements Group {

		private final List<Entry> imports = new ArrayList<>();

		@Override
		public void process(AnnotationMetadata metadata, DeferredImportSelector selector) {
			// 调用了ImportSelector#selectImports 方法
			for (String importClassName : selector.selectImports(metadata)) {
				this.imports.add(new Entry(metadata, importClassName));
			}
		}

		@Override
		public Iterable<Entry> selectImports() {
			// 直接将 Imports 返回
			return this.imports;
		}
	}


	/**
	 * Simple wrapper that allows annotated source classes to be dealt with
	 * in a uniform manner, regardless of how they are loaded.
	 */
	private class SourceClass implements Ordered {

		private final Object source;  // Class or MetadataReader

		private final AnnotationMetadata metadata;

		public SourceClass(Object source) {
			this.source = source;
			if (source instanceof Class) {
				this.metadata = new StandardAnnotationMetadata((Class<?>) source, true);
			}
			else {
				this.metadata = ((MetadataReader) source).getAnnotationMetadata();
			}
		}

		public final AnnotationMetadata getMetadata() {
			return this.metadata;
		}

		@Override
		public int getOrder() {
			Integer order = ConfigurationClassUtils.getOrder(this.metadata);
			return (order != null ? order : Ordered.LOWEST_PRECEDENCE);
		}

		public Class<?> loadClass() throws ClassNotFoundException {
			if (this.source instanceof Class) {
				return (Class<?>) this.source;
			}
			String className = ((MetadataReader) this.source).getClassMetadata().getClassName();
			return ClassUtils.forName(className, resourceLoader.getClassLoader());
		}

		public boolean isAssignable(Class<?> clazz) throws IOException {
			if (this.source instanceof Class) {
				return clazz.isAssignableFrom((Class<?>) this.source);
			}
			return new AssignableTypeFilter(clazz).match((MetadataReader) this.source, metadataReaderFactory);
		}

		public ConfigurationClass asConfigClass(ConfigurationClass importedBy) throws IOException {
			if (this.source instanceof Class) {
				return new ConfigurationClass((Class<?>) this.source, importedBy);
			}
			return new ConfigurationClass((MetadataReader) this.source, importedBy);
		}

		public Collection<SourceClass> getMemberClasses() throws IOException {
			Object sourceToProcess = this.source;
			if (sourceToProcess instanceof Class) {
				Class<?> sourceClass = (Class<?>) sourceToProcess;
				try {
					Class<?>[] declaredClasses = sourceClass.getDeclaredClasses();
					List<SourceClass> members = new ArrayList<>(declaredClasses.length);
					for (Class<?> declaredClass : declaredClasses) {
						members.add(asSourceClass(declaredClass));
					}
					return members;
				}
				catch (NoClassDefFoundError err) {
					// getDeclaredClasses() failed because of non-resolvable dependencies
					// -> fall back to ASM below
					sourceToProcess = metadataReaderFactory.getMetadataReader(sourceClass.getName());
				}
			}

			// ASM-based resolution - safe for non-resolvable classes as well
			MetadataReader sourceReader = (MetadataReader) sourceToProcess;
			String[] memberClassNames = sourceReader.getClassMetadata().getMemberClassNames();
			List<SourceClass> members = new ArrayList<>(memberClassNames.length);
			for (String memberClassName : memberClassNames) {
				try {
					members.add(asSourceClass(memberClassName));
				}
				catch (IOException ex) {
					// Let's skip it if it's not resolvable - we're just looking for candidates
					if (logger.isDebugEnabled()) {
						logger.debug("Failed to resolve member class [" + memberClassName +
								"] - not considering it as a configuration class candidate");
					}
				}
			}
			return members;
		}

		public SourceClass getSuperClass() throws IOException {
			if (this.source instanceof Class) {
				return asSourceClass(((Class<?>) this.source).getSuperclass());
			}
			return asSourceClass(((MetadataReader) this.source).getClassMetadata().getSuperClassName());
		}

		public Set<SourceClass> getInterfaces() throws IOException {
			Set<SourceClass> result = new LinkedHashSet<>();
			if (this.source instanceof Class) {
				Class<?> sourceClass = (Class<?>) this.source;
				for (Class<?> ifcClass : sourceClass.getInterfaces()) {
					result.add(asSourceClass(ifcClass));
				}
			}
			else {
				for (String className : this.metadata.getInterfaceNames()) {
					result.add(asSourceClass(className));
				}
			}
			return result;
		}

		public Set<SourceClass> getAnnotations() throws IOException {
			Set<SourceClass> result = new LinkedHashSet<>();
			for (String className : this.metadata.getAnnotationTypes()) {
				try {
					result.add(getRelated(className));
				}
				catch (Throwable ex) {
					// An annotation not present on the classpath is being ignored
					// by the JVM's class loading -> ignore here as well.
				}
			}
			return result;
		}

		public Collection<SourceClass> getAnnotationAttributes(String annType, String attribute) throws IOException {
			Map<String, Object> annotationAttributes = this.metadata.getAnnotationAttributes(annType, true);
			if (annotationAttributes == null || !annotationAttributes.containsKey(attribute)) {
				return Collections.emptySet();
			}
			String[] classNames = (String[]) annotationAttributes.get(attribute);
			Set<SourceClass> result = new LinkedHashSet<>();
			for (String className : classNames) {
				result.add(getRelated(className));
			}
			return result;
		}

		private SourceClass getRelated(String className) throws IOException {
			if (this.source instanceof Class) {
				try {
					Class<?> clazz = ClassUtils.forName(className, ((Class<?>) this.source).getClassLoader());
					return asSourceClass(clazz);
				}
				catch (ClassNotFoundException ex) {
					// Ignore -> fall back to ASM next, except for core java types.
					if (className.startsWith("java")) {
						throw new NestedIOException("Failed to load class [" + className + "]", ex);
					}
					return new SourceClass(metadataReaderFactory.getMetadataReader(className));
				}
			}
			return asSourceClass(className);
		}

		@Override
		public boolean equals(Object other) {
			return (this == other || (other instanceof SourceClass &&
					this.metadata.getClassName().equals(((SourceClass) other).metadata.getClassName())));
		}

		@Override
		public int hashCode() {
			return this.metadata.getClassName().hashCode();
		}

		@Override
		public String toString() {
			return this.metadata.getClassName();
		}
	}


	/**
	 * {@link Problem} registered upon detection of a circular {@link Import}.
	 */
	private static class CircularImportProblem extends Problem {

		public CircularImportProblem(ConfigurationClass attemptedImport, Deque<ConfigurationClass> importStack) {
			super(String.format("A circular @Import has been detected: " +
					"Illegal attempt by @Configuration class '%s' to import class '%s' as '%s' is " +
					"already present in the current import stack %s", importStack.element().getSimpleName(),
					attemptedImport.getSimpleName(), attemptedImport.getSimpleName(), importStack),
					new Location(importStack.element().getResource(), attemptedImport.getMetadata()));
		}
	}

}
