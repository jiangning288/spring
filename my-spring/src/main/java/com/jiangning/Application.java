package com.jiangning;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.util.Locale;


public class Application {
	public static void main(String[] args) {
		AnnotationConfigApplicationContext ac=new AnnotationConfigApplicationContext(AppConfig.class);
		A bean = ac.getBean(A.class);
//		new ClassPathXmlApplicationContext("spring.xml");
//		Dao bean = ac.getBean(Dao.class);
//		bean.print();


//		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(AppConfig.class);

//		MessageSource messageSource = context.getBean(MessageSource.class);
//
//		String zhMessage = messageSource.getMessage("user.name", null, null, Locale.CHINA);
//		String enMessage = messageSource.getMessage("user.name", null, null, Locale.ENGLISH);
//
//		System.out.println("zhMessage = " + zhMessage);
//
//		System.out.println("enMessage = " + enMessage);
//
	}

}
