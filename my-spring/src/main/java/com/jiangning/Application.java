package com.jiangning;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;



public class Application {
	public static void main(String[] args) {
		AnnotationConfigApplicationContext ac=new AnnotationConfigApplicationContext(AppConfig.class);
		Dao bean = ac.getBean(Dao.class);
		bean.print();

	}
}
