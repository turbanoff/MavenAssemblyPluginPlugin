package org.turbanov.maven.assembly;

import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.XmlSuppressableInspectionTool;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlTagValue;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.maven.model.MavenArtifact;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;

import java.util.*;

/**
 * @author Andrey Turbanov
 * @since 25.08.2018
 */
public class CheckDependenciesPresent extends XmlSuppressableInspectionTool {
    @Nullable
    @Override
    public ProblemDescriptor[] checkFile(@NotNull PsiFile file, @NotNull InspectionManager manager, boolean isOnTheFly) {
        if (!(file instanceof XmlFile)) {
            return null;
        }
        Module module = ModuleUtilCore.findModuleForPsiElement(file);
        if (module == null) {
            return null;
        }
        XmlTag root = ((XmlFile) file).getRootTag();
        if (root == null || !"assembly".equals(root.getLocalName())) {
            return null;
        }
        XmlTag[] dependencySetsTags = root.findSubTags("dependencySets");
        if (dependencySetsTags.length == 0) {
            return null;
        }

        Collection<MavenArtifact> dependencies = collectDependencies(module);
        if (dependencies == null) {
            return null;
        }

        ProblemsHolder holder = new ProblemsHolder(manager, file, isOnTheFly);
        for (XmlTag dependencySetsTag : dependencySetsTags) {
            XmlTag[] dependencySetTags = dependencySetsTag.findSubTags("dependencySet");
            for (XmlTag dependencySetTag : dependencySetTags) {
                XmlTag[] includesTags = dependencySetTag.findSubTags("includes");
                for (XmlTag includesTag : includesTags) {
                    XmlTag[] includeTags = includesTag.findSubTags("include");
                    for (XmlTag includeTag : includeTags) {
                        XmlTagValue value = includeTag.getValue();
                        String includeText = value.getText();
                        if (!matchSome(includeText, dependencies)) {
                            holder.registerProblem(includeTag, "Include pattern doesn't match any dependency");
                        }
                    }
                }
            }
        }

        return holder.getResultsArray();
    }

    @Nullable
    private Collection<MavenArtifact> collectDependencies(Module module) {
        MavenProjectsManager mavenManager = MavenProjectsManager.getInstance(module.getProject());
        if (!mavenManager.isMavenizedModule(module)) {
            return null;
        }
        MavenProject mavenProject = mavenManager.findProject(module);
        if (mavenProject == null) {
            return null;
        }

        List<MavenArtifact> allDependencies = mavenProject.getDependencies();
        return allDependencies;
    }

    private boolean matchSome(@NotNull String includeText, @NotNull Collection<MavenArtifact> dependencies) {
        for (MavenArtifact dependency : dependencies) {
            if (matches(includeText, dependency)) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(@NotNull String includeText, @NotNull MavenArtifact artifact) {
        StringBuilder sb = new StringBuilder()
                .append(artifact.getGroupId())
                .append(':')
                .append(artifact.getArtifactId());
        String classifier = artifact.getClassifier();
        if (!StringUtil.isEmpty(classifier)) {
            sb.append(':').append(classifier);
        }
        String wholeId = sb.toString();
        return matchAgainst(wholeId, includeText);
    }

    private boolean matchAgainst(@NotNull String value, @NotNull String pattern) {
        String[] tokens = value.split(":");
        String[] patternTokens = pattern.split(":");

        if (patternTokens.length == 5 && tokens.length < 5) {
            // 4th element is the classifier
            if (!"*".equals(patternTokens[3])) {
                // classifier required, cannot be a match
                return false;
            }
            patternTokens = new String[]{patternTokens[0], patternTokens[1], patternTokens[2], patternTokens[4]};
        }

        // fail immediately if pattern tokens outnumber tokens to match
        boolean matched = patternTokens.length <= tokens.length;

        for (int i = 0; matched && i < patternTokens.length; i++) {
            matched = matches(tokens[i], patternTokens[i]);
        }

        // case of starting '*' like '*:jar:*'
        // This really only matches from the end instead.....
        if (!matched && patternTokens.length < tokens.length && isFirstPatternWildcard(patternTokens)) {
            matched = true;
            int tokenOffset = tokens.length - patternTokens.length;
            for (int i = 0; matched && i < patternTokens.length; i++) {
                matched = matches(tokens[i + tokenOffset], patternTokens[i]);
            }
        }

        if (matched) {
            return true;
        }

        return value.contains(pattern);
    }

    private boolean matches(@NotNull String token, @NotNull String pattern) {
        boolean matches;

        if ("*".equals(pattern) || pattern.length() == 0) { // full wildcard and implied wildcard
            matches = true;
        } else if (pattern.startsWith("*") && pattern.endsWith("*")) { // contains wildcard
            String contains = pattern.substring(1, pattern.length() - 1);

            matches = token.contains(contains);
        } else if (pattern.startsWith("*")) { // leading wildcard
            String suffix = pattern.substring(1);
            matches = token.endsWith(suffix);
        } else if (pattern.endsWith("*")) { // trailing wildcard
            String prefix = pattern.substring(0, pattern.length() - 1);

            matches = token.startsWith(prefix);
        } else if (pattern.indexOf('*') > -1) { // wildcards in the middle of a pattern segment
            String[] parts = pattern.split("\\*");
            int lastPartEnd = -1;
            boolean match = true;

            for (String part : parts) {
                int idx = token.indexOf(part);
                if (idx <= lastPartEnd) {
                    match = false;
                    break;
                }

                lastPartEnd = idx + part.length();
            }

            matches = match;
        } else if (pattern.startsWith("[") || pattern.startsWith("(")) { // versions range
            matches = isVersionIncludedInRange(token, pattern);
        } else { // exact match
            matches = token.equals(pattern);
        }
        return matches;
    }

    private boolean isFirstPatternWildcard(@NotNull String[] patternTokens) {
        return patternTokens.length > 0 && "*".equals(patternTokens[0]);
    }

    private boolean isVersionIncludedInRange(@NotNull String version, @NotNull String range) {
        try {
            return VersionRange.createFromVersionSpec(range).containsVersion(new DefaultArtifactVersion(version));
        } catch (InvalidVersionSpecificationException e) {
            return false;
        }
    }
}
