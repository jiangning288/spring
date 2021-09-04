/*
 * Copyright 2002-2017 the original author or authors.
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.util.ObjectUtils;

/**
 * {@code BeanPostProcessor} that detects beans which implement the {@code ApplicationListener}
 * interface. This catches beans that can't reliably be detected by {@code getBeanNamesForType}
 * and related operations which only work against top-level beans.
 *
 * <p>With standard Java serialization, this post-processor won't get serialized as part of
 * {@code DisposableBeanAdapter} to begin with. However, with alternative serialization
 * mechanisms, {@code DisposableBeanAdapter.writeReplace} might not get used at all, so we
 * defensively mark this post-processor's field state as {@code transient}.
 *
 * @author Juergen Hoeller
 * @since 4.3.4
 */
class ApplicationListenerDetector implements DestructionAwareBeanPostProcessor, MergedBeanDefinitionPostProcessor {

	private static final Log logger = LogFactory.getLog(ApplicationListenerDetector.class);

	private final transient AbstractApplicationContext applicationContext;

	private final transient Map<String, Boolean> singletonNames = new ConcurrentHashMap<>(256);


	public ApplicationListenerDetector(AbstractApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}


	@Override
	public void postProcessMergedBeanDefinition(RootBeanDefinition beanDefinition, Class<?> beanType, String beanName) {
		this.singletonNames.put(beanName, beanDefinition.isSingleton());
	}

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) {
		return bean;
	}

	@Override
	// BeanPostProcessor 定义的方法，在每个bean创建时初始化之后应用
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		// 检测到bean是一个 ApplicationListener 应用事件监听器
		if (bean instanceof ApplicationListener) {
			// potentially not detected as a listener by getBeanNamesForType retrieval
			Boolean flag = this.singletonNames.get(beanName);
			if (Boolean.TRUE.equals(flag)) {
				// singleton bean (top-level or inner): register on the fly
				// 如果当前 ApplicationListener bean scope 是 singleton 单例模式，
				// 将实现了ApplicationListener接口的bean注册到事件发布器applicationEventMulticaster中
				// 注意看addApplicationListener()方法，是注册到事件发布器applicationEventMulticaster中
				// 那么为什么前面registerListeners已经解析过了，为什么还要进行解析？
				// 为了防止漏网之鱼；比如Bean是个懒加载@Lazy注解，在registerListeners方法是解析不到的。
				this.applicationContext.addApplicationListener((ApplicationListener<?>) bean);
			}
			else if (Boolean.FALSE.equals(flag)) {
				// 如果当前 ApplicationListener bean scope 不是 singleton 单例模式，
				// 则尝试输出警告日志，说明情况
				if (logger.isWarnEnabled() && !this.applicationContext.containsBean(beanName)) {
					// inner bean with other scope - can't reliably process events
					logger.warn("Inner bean '" + beanName + "' implements ApplicationListener interface " +
							"but is not reachable for event multicasting by its containing ApplicationContext " +
							"because it does not have singleton scope. Only top-level listener beans are allowed " +
							"to be of non-singleton scope.");
				}
				this.singletonNames.remove(beanName);
			}
		}
		return bean;
	}

	// DestructionAwareBeanPostProcessor 接口定义的方法,
	// 在 bean 销毁前被调用
	@Override
	public void postProcessBeforeDestruction(Object bean, String beanName) {
		if (bean instanceof ApplicationListener) {
			// 如果当前 bean 是一个 ApplicationListener 才执行这段逻辑
			try {
				// 当前  bean 是一个 ApplicationListener, 现在该bean即将销毁，
				// 因此从应用上下文的事件多播器上将其移除
				ApplicationEventMulticaster multicaster = this.applicationContext.getApplicationEventMulticaster();
				multicaster.removeApplicationListener((ApplicationListener<?>) bean);
				multicaster.removeApplicationListenerBean(beanName);
			}
			catch (IllegalStateException ex) {
				// ApplicationEventMulticaster not initialized yet - no need to remove a listener
			}
		}
	}

	@Override
	public boolean requiresDestruction(Object bean) {
		// 用于检测对于某个 bean 是否要调用 postProcessBeforeDestruction 方法
		// 该实现表明仅在 bean 是一个 ApplicationListener 时才调用上面的
		// postProcessBeforeDestruction
		return (bean instanceof ApplicationListener);
	}


	@Override
	public boolean equals(Object other) {
		return (this == other || (other instanceof ApplicationListenerDetector &&
				this.applicationContext == ((ApplicationListenerDetector) other).applicationContext));
	}

	@Override
	public int hashCode() {
		return ObjectUtils.nullSafeHashCode(this.applicationContext);
	}

}
