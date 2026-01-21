package com.mapfre.karate.runner;

import com.intuit.karate.junit5.Karate;

class RunAllTest {

    @Karate.Test
    Karate runAll() {
        return Karate.run("classpath:features/").relativeTo(getClass());
    }
}
