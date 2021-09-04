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

package org.springframework.context.event;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.aop.scope.ScopedObject;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;

/**
 * Registers {@link EventListener} methods as individual {@link ApplicationListener} instances.
 * Implements {@link BeanFactoryPostProcessor} (as of 5.1) primarily for early retrieval,
 * avoiding AOP checks for this processor bean and its {@link EventListenerFactory} delegates.
 *
 * @author Stephane Nicoll
 * @author Juergen Hoeller
 * @since 4.2
 * @see EventListenerFactory
 * @see DefaultEventListenerFactory
 *
 * 这个后置处理器, 主要是处理 @EventListener 注解的.
 *  1. 解析 @EventListener , 获取拦截方法
 *  2. 对拦截方法进行转换, 变成 ApplicationListener
 *  3. 将转换的 ApplicationListener, 放到spring容器中
 *
 * 通过实现接口ApplicationContextAware，容器会将当前应用上下文ApplicationContext告诉EventListenerMethodProcessor,
 * 这是EventListenerMethodProcessor用于检测发现@EventListener注解方法的来源，生成的ApplicationListener也放到该应用上下文。
 *
 * 通过实现接口BeanFactoryPostProcessor,EventListenerMethodProcessor变成了一个BeanFactory的后置处理器，也就是说，
 * 在容器启动过程中的后置处理阶段,启动过程会调用EventListenerMethodProcessor的方法postProcessBeanFactory。
 * 在这个方法中,EventListenerMethodProcessor会找到容器中所有类型为EventListenerFactory的bean,最终@EventListener注解方法的检测发现，
 * 以及ApplicationListener实例的生成和注册，靠的是这些EventListenerFactory组件。
 *
 * 通过实现接口SmartInitializingSingleton,在容器启动过程中所有单例bean创建阶段(此阶段完成前，这些bean并不会供外部使用)的末尾，
 * EventListenerMethodProcessor的方法afterSingletonsInstantiated会被调用。在这里，EventListenerMethodProcessor会便利容器中所有的bean,
 * 进行@EventListener注解方法的检测发现，以及ApplicationListener实例的生成和注册。
 *
 */
public class EventListenerMethodProcessor
		implements SmartInitializingSingleton, ApplicationContextAware, BeanFactoryPostProcessor {

	protected final Log logger = LogFactory.getLog(getClass());

	// 用于记录检测发现`@EventListener`注解方法，生成和注册`ApplicationListener`实例的应用上下文
	@Nullable
	private ConfigurableApplicationContext applicationContext;

	// 记录当前 BeanFactory， 实际上这个变量可用可不用，因为通过 applicationContext 也可以找到当前 BeanFactory
	@Nullable
	private ConfigurableListableBeanFactory beanFactory;

	// 记录从容器中找到的所有 EventListenerFactory
	@Nullable
	private List<EventListenerFactory> eventListenerFactories;

	private final EventExpressionEvaluator evaluator = new EventExpressionEvaluator();

	// 缓存机制，记住那些根本任何方法上没有使用注解 @EventListener 的类，避免处理过程中二次处理
	private final Set<Class<?>> nonAnnotatedClasses = Collections.newSetFromMap(new ConcurrentHashMap<>(64));


	@Override
	public void setApplicationContext(ApplicationContext applicationContext) {
		Assert.isTrue(applicationContext instanceof ConfigurableApplicationContext,
				"ApplicationContext does not implement ConfigurableApplicationContext");
		this.applicationContext = (ConfigurableApplicationContext) applicationContext;
	}

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		// 从容器中找到所有的  EventListenerFactory 组件
		// 默认有两个
		// 1。 EventListenerFactory :
		// 如：TransactionalEventListenerFactory --
		// 用于支持使用 @TransactionalEventListener 注解的事件监听器, @TransactionalEventListener 是一种特殊的@EventListener，它定义的事件监听器应用于事务提交或者回滚的某些特殊时机，
		// 由 ProxyTransactionManagementConfiguration 注册到容器
		// 2。DefaultEventListenerFactory -- 系统缺省, 最低优先级，如果其他 EventListenerFactory 都不支持的时候使用

		Map<String, EventListenerFactory> beans = beanFactory.getBeansOfType(EventListenerFactory.class, false, false);
		List<EventListenerFactory> factories = new ArrayList<>(beans.values());
		AnnotationAwareOrderComparator.sort(factories);
		this.eventListenerFactories = factories;
	}


	@Override
	public void afterSingletonsInstantiated() {
		ConfigurableListableBeanFactory beanFactory = this.beanFactory;
		Assert.state(this.beanFactory != null, "No ConfigurableListableBeanFactory set");
		// 这里获取容器中所有bean组件的名称，
		String[] beanNames = beanFactory.getBeanNamesForType(Object.class);
		// 遍历每个bean组件，检测其中@EventListener注解方法，生成和注册ApplicationListener实例
		for (String beanName : beanNames) {
			if (!ScopedProxyUtils.isScopedTarget(beanName)) {
				Class<?> type = null;
				try {
					type = AutoProxyUtils.determineTargetClass(beanFactory, beanName);
				}
				catch (Throwable ex) {
					// An unresolvable bean type, probably from a lazy bean - let's ignore it.
					if (logger.isDebugEnabled()) {
						logger.debug("Could not resolve target class for bean with name '" + beanName + "'", ex);
					}
				}
				if (type != null) {
					if (ScopedObject.class.isAssignableFrom(type)) {
						try {
							Class<?> targetClass = AutoProxyUtils.determineTargetClass(
									beanFactory, ScopedProxyUtils.getTargetBeanName(beanName));
							if (targetClass != null) {
								type = targetClass;
							}
						}
						catch (Throwable ex) {
							// An invalid scoped proxy arrangement - let's ignore it.
							if (logger.isDebugEnabled()) {
								logger.debug("Could not resolve target bean for scoped proxy '" + beanName + "'", ex);
							}
						}
					}
					try {
						//处理注解
						processBean(beanName, type);
					}
					catch (Throwable ex) {
						throw new BeanInitializationException("Failed to process @EventListener " +
								"annotation on bean with name '" + beanName + "'", ex);
					}
				}
			}
		}
	}

	private void processBean(final String beanName, final Class<?> targetType) {
		if (!this.nonAnnotatedClasses.contains(targetType) && !isSpringContainerClass(targetType)) {
			Map<Method, EventListener> annotatedMethods = null;
			try {
				//获取标注了 @EventListener 注解的监听方法
				annotatedMethods = MethodIntrospector.selectMethods(targetType,
						(MethodIntrospector.MetadataLookup<EventListener>) method ->
								AnnotatedElementUtils.findMergedAnnotation(method, EventListener.class));
			}
			catch (Throwable ex) {
				// An unresolvable type in a method signature, probably from a lazy bean - let's ignore it.
				if (logger.isDebugEnabled()) {
					logger.debug("Could not resolve methods for bean with name '" + beanName + "'", ex);
				}
			}
			// 如果当前类 targetType 中没有任何使用了 注解 @EventListener 的方法，则将该类保存到
			// 缓存 nonAnnotatedClasses, 从而避免当前处理方法重入该类，其目的应该是为了提高效率，
			if (CollectionUtils.isEmpty(annotatedMethods)) {
				this.nonAnnotatedClasses.add(targetType);
				if (logger.isTraceEnabled()) {
					logger.trace("No @EventListener annotations found on bean class: " + targetType.getName());
				}
			}
			else {
				// Non-empty set of methods
				ConfigurableApplicationContext context = this.applicationContext;
				Assert.state(context != null, "No ApplicationContext set");
				// 注意，这里使用到了 this.eventListenerFactories, 这些 EventListenerFactory 是在
				// 该类 postProcessBeanFactory 方法调用时被记录的
				List<EventListenerFactory> factories = this.eventListenerFactories;
				Assert.state(factories != null, "EventListenerFactory List not initialized");
				for (Method method : annotatedMethods.keySet()) {
					for (EventListenerFactory factory : factories) {
						// 如果当前 EventListenerFactory factory 支持处理该 @EventListener 注解的方法,
						// 则使用它创建 ApplicationListener
						if (factory.supportsMethod(method)) {
							Method methodToUse = AopUtils.selectInvocableMethod(method, context.getType(beanName));
							//为监听方法创建 ApplicationListener
							ApplicationListener<?> applicationListener =
									factory.createApplicationListener(beanName, targetType, methodToUse);
							if (applicationListener instanceof ApplicationListenerMethodAdapter) {
								((ApplicationListenerMethodAdapter) applicationListener).init(context, this.evaluator);
							}
							//将创建的 ApplicationListener 加入到容器中
							context.addApplicationListener(applicationListener);
							break;
						}
					}
				}
				if (logger.isDebugEnabled()) {
					logger.debug(annotatedMethods.size() + " @EventListener methods processed on bean '" +
							beanName + "': " + annotatedMethods);
				}
			}
		}
	}

	/**
	 * Determine whether the given class is an {@code org.springframework}
	 * bean class that is not annotated as a user or test {@link Component}...
	 * which indicates that there is no {@link EventListener} to be found there.
	 * @since 5.1
	 */
	private static boolean isSpringContainerClass(Class<?> clazz) {
		return (clazz.getName().startsWith("org.springframework.") &&
				!AnnotatedElementUtils.isAnnotated(ClassUtils.getUserClass(clazz), Component.class));
	}

}
