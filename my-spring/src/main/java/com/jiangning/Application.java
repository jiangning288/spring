package com.jiangning;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;


public class Application {
	public static void main(String[] args) {
		AnnotationConfigApplicationContext ac=new AnnotationConfigApplicationContext(AppConfig.class);
		new ClassPathXmlApplicationContext("spring.xml");
		Dao bean = ac.getBean(Dao.class);
		bean.print();

	}
}
