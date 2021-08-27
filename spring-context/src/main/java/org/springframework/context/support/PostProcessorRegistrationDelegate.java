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

package org.springframework.context.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.lang.Nullable;

/**
 * Delegate for AbstractApplicationContext's post-processor handling.
 *
 * @author Juergen Hoeller
 * @since 4.0
 */
final class PostProcessorRegistrationDelegate {

	private PostProcessorRegistrationDelegate() {
	}


	public static void invokeBeanFactoryPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanFactoryPostProcessor> beanFactoryPostProcessors) {

		// Invoke BeanDefinitionRegistryPostProcessors first, if any.
		Set<String> processedBeans = new HashSet<>();

		if (beanFactory instanceof BeanDefinitionRegistry) {
			BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
			//放程序员手动添加的beanFactoryPostProcessors
			List<BeanFactoryPostProcessor> regularPostProcessors = new ArrayList<>();
			//放程序员手动添加的BeanDefinitionRegistryPostProcessor
			List<BeanDefinitionRegistryPostProcessor> registryProcessors = new ArrayList<>();

			for (BeanFactoryPostProcessor postProcessor : beanFactoryPostProcessors) {
				if (postProcessor instanceof BeanDefinitionRegistryPostProcessor) {
					BeanDefinitionRegistryPostProcessor registryProcessor =
							(BeanDefinitionRegistryPostProcessor) postProcessor;
					//直接执行BeanDefinitionRegistryPostProcessor接口的postProcessBeanDefinitionRegistry方法
					registryProcessor.postProcessBeanDefinitionRegistry(registry);
					//添加到registryProcessors(用于最后执行postProcessBeanFactory方法)
					registryProcessors.add(registryProcessor);
				}
				else {
					//否则只是普通的BeanFactoryPostProcessor
					//直接添加到regularPostProcessors(用于最后执行postProcessBeanFactory方法)
					regularPostProcessors.add(postProcessor);
				}
			}

			// Do not initialize FactoryBeans here: We need to leave all regular beans
			// uninitialized to let the bean factory post-processors apply to them!
			// Separate between BeanDefinitionRegistryPostProcessors that implement
			// PriorityOrdered, Ordered, and the rest.
			//放spring内部自己的BeanDefinitionRegistryPostProcessor
			List<BeanDefinitionRegistryPostProcessor> currentRegistryProcessors = new ArrayList<>();

			//调用所有实现PriorityOrdered接口的BeanDefinitionRegistryPostProcessor实现类
			// First, invoke the BeanDefinitionRegistryPostProcessors that implement PriorityOrdered.
			//getBeanNamesForType  根据bean的类型获取bean的名字
			// ConfigurationClassPostProcessor（实现了BeanDefinitionRegistryPostProcessor ）
			String[] postProcessorNames =
					beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);

			//这个地方可以得到一个BeanFactoryPostProcessor，因为是spring默认在最开始自己注册的
			//为什么要在最开始注册这个呢？
			//因为spring的工厂需要许解析去扫描等等功能
			//而这些功能都是需要在spring工厂初始化完成之前执行
			//要么在工厂最开始的时候、要么在工厂初始化之中，反正不能再之后
			//因为如果在之后就没有意义，因为那个时候已经需要使用工厂了
			//所以这里spring在一开始就注册了一个BeanFactoryPostProcessor，用来插手springfactory的实例化过程
			//在这个地方断点可以知道这个类叫做ConfigurationClassPostProcessor
			//ConfigurationClassPostProcessor那么这个类能干嘛呢？可以参考源码
			//下面我们对这个牛逼哄哄的类（他能插手spring工厂的实例化过程还不牛逼吗？）重点解释
			for (String ppName : postProcessorNames) {
				//校验是否实现了PriorityOrdered接口
				if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
					// 获取ppName对应的bean实例, 添加到currentRegistryProcessors中,
					// beanFactory.getBean: 这边getBean方法会触发创建ppName对应的bean对象, 目前暂不深入解析
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					// 将要被执行的加入processedBeans，避免后续重复执行
					processedBeans.add(ppName);
				}
			}
			//排序不重要，况且currentRegistryProcessors这里也只有一个数据（根据是否实现PriorityOrdered、Ordered接口和order值来排序）
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			//合并BeanDefinitionRegistryPostProcessor这个list，不重要(为什么要合并，因为还有自己的)
			// (用于最后执行postProcessBeanFactory方法)
			registryProcessors.addAll(currentRegistryProcessors);
			//最重要。注意这里是方法调用
			//执行所有BeanDefinitionRegistryPostProcessor——此处是spring内部自己的
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);
			//执行完成了所有BeanDefinitionRegistryPostProcessor
			//这个list只是一个临时变量，故而要清除
			currentRegistryProcessors.clear();


			//调用所有实现了Ordered接口的BeanDefinitionRegistryPostProcessor实现类（过程跟上面的步骤基本一样）
			// Next, invoke the BeanDefinitionRegistryPostProcessors that implement Ordered.
			//找出所有实现BeanDefinitionRegistryPostProcessor接口的类, 这边重复查找是因为执行完上面的BeanDefinitionRegistryPostProcessor,
			//可能会新增了其他的BeanDefinitionRegistryPostProcessor, 因此需要重新查找

			postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			for (String ppName : postProcessorNames) {
				// 校验是否实现了Ordered接口，并且还未执行过
				if (!processedBeans.contains(ppName) && beanFactory.isTypeMatch(ppName, Ordered.class)) {//且是Ordered
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					processedBeans.add(ppName);
				}
			}
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			registryProcessors.addAll(currentRegistryProcessors);
			//执行所有BeanDefinitionRegistryPostProcessor——此处是spring内部自己的
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);
			currentRegistryProcessors.clear();

			//最后, 调用所有剩下的BeanDefinitionRegistryPostProcessors
			// Finally, invoke all other BeanDefinitionRegistryPostProcessors until no further ones appear.
			boolean reiterate = true;
			while (reiterate) {
				reiterate = false;
				//找出所有实现BeanDefinitionRegistryPostProcessor接口的类
				postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
				for (String ppName : postProcessorNames) {
					//跳过已经执行过的
					if (!processedBeans.contains(ppName)) {
						currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
						// 如果有BeanDefinitionRegistryPostProcessor被执行, 则有可能会产生新的BeanDefinitionRegistryPostProcessor,
						// 因此这边将reiterate赋值为true, 代表需要再循环查找一次
						processedBeans.add(ppName);
						reiterate = true;
					}
				}
				sortPostProcessors(currentRegistryProcessors, beanFactory);
				registryProcessors.addAll(currentRegistryProcessors);
				//遍历currentRegistryProcessors, 执行postProcessBeanDefinitionRegistry方法
				invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);
				currentRegistryProcessors.clear();
			}

			// Now, invoke the postProcessBeanFactory callback of all processors handled so far.
			//调用所有BeanDefinitionRegistryPostProcessor的postProcessBeanFactory方法(BeanDefinitionRegistryPostProcessor继承自BeanFactoryPostProcessor)
			invokeBeanFactoryPostProcessors(registryProcessors, beanFactory);
			//最后, 调用入参beanFactoryPostProcessors中的普通BeanFactoryPostProcessor的postProcessBeanFactory方法
			invokeBeanFactoryPostProcessors(regularPostProcessors, beanFactory);
		}

		else {
			// Invoke factory processors registered with the context instance.
			invokeBeanFactoryPostProcessors(beanFactoryPostProcessors, beanFactory);
		}

		// 到这里 , 入参beanFactoryPostProcessors和容器中的所有BeanDefinitionRegistryPostProcessor已经全部处理完毕,
		// 下面开始处理容器中的所有BeanFactoryPostProcessor
		// -----------------------------------



		// Do not initialize FactoryBeans here: We need to leave all regular beans
		// uninitialized to let the bean factory post-processors apply to them!
		//找出所有实现BeanFactoryPostProcessor接口的类
		String[] postProcessorNames =
				beanFactory.getBeanNamesForType(BeanFactoryPostProcessor.class, true, false);

		// Separate between BeanFactoryPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		List<BeanFactoryPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		List<String> orderedPostProcessorNames = new ArrayList<>();
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		for (String ppName : postProcessorNames) {
			if (processedBeans.contains(ppName)) {
				// skip - already processed in first phase above
			}
			else if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				priorityOrderedPostProcessors.add(beanFactory.getBean(ppName, BeanFactoryPostProcessor.class));
			}
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, invoke the BeanFactoryPostProcessors that implement PriorityOrdered.
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		invokeBeanFactoryPostProcessors(priorityOrderedPostProcessors, beanFactory);

		// Next, invoke the BeanFactoryPostProcessors that implement Ordered.
		List<BeanFactoryPostProcessor> orderedPostProcessors = new ArrayList<>();
		for (String postProcessorName : orderedPostProcessorNames) {
			orderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		sortPostProcessors(orderedPostProcessors, beanFactory);
		invokeBeanFactoryPostProcessors(orderedPostProcessors, beanFactory);

		// Finally, invoke all other BeanFactoryPostProcessors.
		List<BeanFactoryPostProcessor> nonOrderedPostProcessors = new ArrayList<>();
		for (String postProcessorName : nonOrderedPostProcessorNames) {
			nonOrderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		invokeBeanFactoryPostProcessors(nonOrderedPostProcessors, beanFactory);

		// Clear cached merged bean definitions since the post-processors might have
		// modified the original metadata, e.g. replacing placeholders in values...
		beanFactory.clearMetadataCache();
		/** 【总结】
		 * 整个 invokeBeanFactoryPostProcessors 方法操作了 3 种 bean 对象：
		 *
		 * 1。入参 beanFactoryPostProcessors：这个我们在代码块1中解析过，拿的是 AbstractApplicationContext 类的 beanFactoryPostProcessors 属性值，也就是在之前已经添加到 beanFactoryPostProcessors 中的 BeanFactoryPostProcessor。
		 * 2。BeanDefinitionRegistryPostProcessor 接口实现类：实现了 BeanDefinitionRegistryPostProcessor 接口，并且注册到 Spring IoC容器中。
		 * 3。常规 BeanFactoryPostProcessor 接口实现类：实现了 BeanFactoryPostProcessor 接口，并且注册到 Spring IoC容器中。
		 * 操作3种 bean 对象具体指的是调用它们重写的方法，调用实现方法时会遵循以下的优先级：
		 *
		 * 第一优先级：入参 beanFactoryPostProcessors 中的 BeanDefinitionRegistryPostProcessor， 调用 postProcessBeanDefinitionRegistry 方法。
		 * 第二优先级：BeanDefinitionRegistryPostProcessor 接口实现类，并且实现了 PriorityOrdered 接口，调用 postProcessBeanDefinitionRegistry 方法
		 * 第三优先级：BeanDefinitionRegistryPostProcessor 接口实现类，并且实现了 Ordered 接口，调用 postProcessBeanDefinitionRegistry 方法
		 * 第四优先级：除去第二优先级和第三优先级，剩余的 BeanDefinitionRegistryPostProcessor 接口实现类，调用 postProcessBeanDefinitionRegistry 方法
		 * 第五优先级：所有 BeanDefinitionRegistryPostProcessor 接口实现类，调用 postProcessBeanFactory。
		 * 第六优先级：入参 beanFactoryPostProcessors 中的常规 BeanFactoryPostProcessor，调用 postProcessBeanFactory
		 * 第七优先级：常规 BeanFactoryPostProcessor 接口实现类，并且实现了 PriorityOrdered 接口，调用 postProcessBeanFactory 方法
		 * 第八优先级：常规 BeanFactoryPostProcessor 接口实现类，并且实现了 Ordered 接口，调用 postProcessBeanFactory 方法
		 * 第九优先级：除去第七优先级和第八优先级，剩余的常规 BeanFactoryPostProcessor 接口的实现类，调用 postProcessBeanFactory
		 */
	}

	public static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, AbstractApplicationContext applicationContext) {

		String[] postProcessorNames = beanFactory.getBeanNamesForType(BeanPostProcessor.class, true, false);

		// Register BeanPostProcessorChecker that logs an info message when
		// a bean is created during BeanPostProcessor instantiation, i.e. when
		// a bean is not eligible for getting processed by all BeanPostProcessors.
		int beanProcessorTargetCount = beanFactory.getBeanPostProcessorCount() + 1 + postProcessorNames.length;
		beanFactory.addBeanPostProcessor(new BeanPostProcessorChecker(beanFactory, beanProcessorTargetCount));

		// Separate between BeanPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		List<BeanPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		List<BeanPostProcessor> internalPostProcessors = new ArrayList<>();
		List<String> orderedPostProcessorNames = new ArrayList<>();
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		for (String ppName : postProcessorNames) {
			if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
				priorityOrderedPostProcessors.add(pp);
				if (pp instanceof MergedBeanDefinitionPostProcessor) {
					internalPostProcessors.add(pp);
				}
			}
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, register the BeanPostProcessors that implement PriorityOrdered.
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, priorityOrderedPostProcessors);

		// Next, register the BeanPostProcessors that implement Ordered.
		List<BeanPostProcessor> orderedPostProcessors = new ArrayList<>();
		for (String ppName : orderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			orderedPostProcessors.add(pp);
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		sortPostProcessors(orderedPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, orderedPostProcessors);

		// Now, register all regular BeanPostProcessors.
		List<BeanPostProcessor> nonOrderedPostProcessors = new ArrayList<>();
		for (String ppName : nonOrderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			nonOrderedPostProcessors.add(pp);
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		registerBeanPostProcessors(beanFactory, nonOrderedPostProcessors);

		// Finally, re-register all internal BeanPostProcessors.
		sortPostProcessors(internalPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, internalPostProcessors);

		// Re-register post-processor for detecting inner beans as ApplicationListeners,
		// moving it to the end of the processor chain (for picking up proxies etc).
		beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(applicationContext));
	}

	private static void sortPostProcessors(List<?> postProcessors, ConfigurableListableBeanFactory beanFactory) {
		Comparator<Object> comparatorToUse = null;
		if (beanFactory instanceof DefaultListableBeanFactory) {
			comparatorToUse = ((DefaultListableBeanFactory) beanFactory).getDependencyComparator();
		}
		if (comparatorToUse == null) {
			comparatorToUse = OrderComparator.INSTANCE;
		}
		postProcessors.sort(comparatorToUse);
	}

	/**
	 * Invoke the given BeanDefinitionRegistryPostProcessor beans.
	 */
	private static void invokeBeanDefinitionRegistryPostProcessors(
			Collection<? extends BeanDefinitionRegistryPostProcessor> postProcessors, BeanDefinitionRegistry registry) {

		for (BeanDefinitionRegistryPostProcessor postProcessor : postProcessors) {
			postProcessor.postProcessBeanDefinitionRegistry(registry);
		}
	}

	/**
	 * Invoke the given BeanFactoryPostProcessor beans.
	 */
	private static void invokeBeanFactoryPostProcessors(
			Collection<? extends BeanFactoryPostProcessor> postProcessors, ConfigurableListableBeanFactory beanFactory) {

		for (BeanFactoryPostProcessor postProcessor : postProcessors) {
			postProcessor.postProcessBeanFactory(beanFactory);
		}
	}

	/**
	 * Register the given BeanPostProcessor beans.
	 */
	private static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanPostProcessor> postProcessors) {

		for (BeanPostProcessor postProcessor : postProcessors) {
			beanFactory.addBeanPostProcessor(postProcessor);
		}
	}


	/**
	 * BeanPostProcessor that logs an info message when a bean is created during
	 * BeanPostProcessor instantiation, i.e. when a bean is not eligible for
	 * getting processed by all BeanPostProcessors.
	 */
	private static final class BeanPostProcessorChecker implements BeanPostProcessor {

		private static final Log logger = LogFactory.getLog(BeanPostProcessorChecker.class);

		private final ConfigurableListableBeanFactory beanFactory;

		private final int beanPostProcessorTargetCount;

		public BeanPostProcessorChecker(ConfigurableListableBeanFactory beanFactory, int beanPostProcessorTargetCount) {
			this.beanFactory = beanFactory;
			this.beanPostProcessorTargetCount = beanPostProcessorTargetCount;
		}

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			return bean;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			if (!(bean instanceof BeanPostProcessor) && !isInfrastructureBean(beanName) &&
					this.beanFactory.getBeanPostProcessorCount() < this.beanPostProcessorTargetCount) {
				if (logger.isInfoEnabled()) {
					logger.info("Bean '" + beanName + "' of type [" + bean.getClass().getName() +
							"] is not eligible for getting processed by all BeanPostProcessors " +
							"(for example: not eligible for auto-proxying)");
				}
			}
			return bean;
		}

		private boolean isInfrastructureBean(@Nullable String beanName) {
			if (beanName != null && this.beanFactory.containsBeanDefinition(beanName)) {
				BeanDefinition bd = this.beanFactory.getBeanDefinition(beanName);
				return (bd.getRole() == RootBeanDefinition.ROLE_INFRASTRUCTURE);
			}
			return false;
		}
	}

}
