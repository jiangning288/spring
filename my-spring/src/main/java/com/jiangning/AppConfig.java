package com.jiangning;

import org.springframework.context.annotation.*;

@Configuration
@ComponentScan
@EnableAspectJAutoProxy
@Profile("dev")
public class AppConfig {
}
