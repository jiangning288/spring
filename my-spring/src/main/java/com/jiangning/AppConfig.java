package com.jiangning;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.*;
import org.springframework.context.support.ResourceBundleMessageSource;

@Configuration
@ComponentScan("com.jiangning")
public class AppConfig {

//	@Bean(name = "messageSource")
//	public MessageSource getMessageSource() {
//		ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
//		messageSource.setDefaultEncoding("UTF-8");
//		messageSource.addBasenames("message", "message_en");
//		return messageSource;
//
//	}



}
