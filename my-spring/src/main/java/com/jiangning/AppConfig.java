package com.jiangning;

import org.springframework.context.annotation.*;

@Configuration
@ComponentScan("com.jiangning")
//@Import(MyImportSelector.class)
public class AppConfig {

	@Bean
	public Hello hello(){
		return new Hello();
	}

	@Bean
	public  IndexDao indexDao() {
		return new IndexDao();
	}

	@Bean
	public static Test test(){
		return new Test();
	}



}
