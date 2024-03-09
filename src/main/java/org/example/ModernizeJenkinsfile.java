package org.example;

import org.openrewrite.Recipe;

public class ModernizeJenkinsfile extends Recipe{
    @Override
    public String getDisplayName() {
        return "Modernize Jenkins file";
    }

    @Override
    public String getDescription() {
        return "Mordernize the Jenkins file to the latest version";
    }
}
