package org.example;


import java.util.LinkedHashSet;

public class RewriteRunner {

    public static void main(String[] args) {

        RewriteRun rewriteRun = new RewriteRun();
        rewriteRun.activeRecipes = new LinkedHashSet<>();
        rewriteRun.activeRecipes.add("com.yourorg.AddLicenseHeaderExample");
        rewriteRun.execute();
    }
}
