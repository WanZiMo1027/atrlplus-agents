package com.yuntian;

import com.yuntian.config.SkillConfig;
import com.yuntian.dto.AgentChatRequest;
import com.yuntian.dto.ChatMessageItem;
import com.yuntian.memory.MemoryService;
import com.yuntian.service.AgentService;
import com.yuntian.service.QQAssistant;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.spring.AiServiceScannerProcessor;
import dev.langchain4j.service.spring.AiServicesAutoConfig;
import dev.langchain4j.skills.ClassPathSkillLoader;
import dev.langchain4j.skills.Skills;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SkillIntegrationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AiServicesAutoConfig.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void springServiceActivatesSkillReadsReferenceAndParsesDecision() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(QQAssistant.class);
            var model = context.getBean(RecordingModel.class);
            var skills = context.getBean(Skills.class);
            var skill = ClassPathSkillLoader.loadSkill("skills/dafeiyu-bot-style");
            var reference = skill.resources().stream()
                    .filter(r -> r.relativePath().equals("references/conversation-acts.md"))
                    .findFirst().orElseThrow();

            // 模拟两次工具调用；真实执行器必须将预加载正文和参考文件返回给模型。
            model.responder = request -> switch (model.requests.size()) {
                case 1 -> {
                    assertThat(request.toolSpecifications()).extracting(t -> t.name())
                            .contains("activate_skill", "read_skill_resource");
                    assertSystemData(request, "MEMORY_FIRST");
                    yield tool("activation", "activate_skill", "{\"skill_name\":\"dafeiyu-bot-style\"}");
                }
                case 2 -> {
                    assertThat(toolResult(request, "activate_skill")).contains(skill.content());
                    yield tool("reference", "read_skill_resource",
                            "{\"skill_name\":\"dafeiyu-bot-style\",\"relative_path\":\"references/conversation-acts.md\"}");
                }
                default -> {
                    assertThat(toolResult(request, "read_skill_resource")).contains(reference.content());
                    yield AiMessage.from("{\"shouldReply\":true,\"reply\":\"在\"}");
                }
            };
            var assistant = context.getBean(QQAssistant.class);
            var decision = assistant.chat("group-1", "MEMORY_FIRST", skills.formatAvailableSkills(), "群友：大肥鱼");
            assertThat(decision.shouldReply()).isTrue();
            assertThat(decision.reply()).isEqualTo("在");
            assertThat(model.requests).hasSize(3);

            // 同一会话下一轮替换长期记忆，仍保留已加载的 Skill 上下文。
            model.responder = request -> {
                assertSystemData(request, "MEMORY_SECOND");
                assertThat(toolResult(request, "activate_skill")).contains(skill.content());
                return AiMessage.from("{\"shouldReply\":false,\"reply\":\"\"}");
            };
            var next = assistant.chat("group-1", "MEMORY_SECOND", skills.formatAvailableSkills(), "群友甲：晚点吃饭");
            assertThat(next.shouldReply()).isFalse();
            assertThat(next.reply()).isEmpty();
        });
    }

    @Test
    void agentServiceInjectsCatalogueAndSavesEmptyReplyForSilence() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var model = context.getBean(RecordingModel.class);
            model.responder = request -> {
                assertSystemData(request, "没有相关长期记忆。");
                return AiMessage.from("{\"shouldReply\":false,\"reply\":\"\"}");
            };
            var result = context.getBean(AgentService.class).chat(new AgentChatRequest(
                    "group-silent", List.of(new ChatMessageItem("event-1", "群友甲", "晚点吃饭"))));
            assertThat(result.shouldReply()).isFalse();
            assertThat(result.reply()).isEmpty();
            verify(context.getBean(MemoryService.class)).saveConversation(
                    "group-silent", "以下是QQ群中刚刚连续出现的消息：\n\n群友甲：晚点吃饭\n", "");
        });
    }

    @Test
    void classpathLoaderReadsSkillAndResourcesFromJar(@TempDir Path tempDir) throws Exception {
        Path jar = tempDir.resolve("skills.jar");
        Path root = Path.of("src/main/resources/skills");
        // 打包真实资源，再用只含此 JAR 的 ClassLoader 加载，排除源码路径回退。
        try (var output = new JarOutputStream(Files.newOutputStream(jar)); var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                String name = "skills" + (path.equals(root) ? "" : "/" + root.relativize(path).toString().replace('\\', '/'));
                output.putNextEntry(new JarEntry(name + (Files.isDirectory(path) ? "/" : "")));
                if (Files.isRegularFile(path)) {
                    Files.copy(path, output);
                }
                output.closeEntry();
            }
        }
        try (var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, null)) {
            var skills = ClassPathSkillLoader.loadSkills("skills", loader);
            assertThat(skills).hasSize(1);
            assertThat(skills.get(0).name()).isEqualTo("dafeiyu-bot-style");
            assertThat(skills.get(0).resources()).extracting(r -> r.relativePath())
                    .contains("references/examples.md", "references/conversation-acts.md")
                    .doesNotContain("scripts/analyze_chat_export.py");
        }
    }

    private static void assertSystemData(ChatRequest request, String memory) {
        var system = request.messages().stream().filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast).findFirst().orElseThrow().text();
        assertThat(system).contains(memory, "<available_skills>", "dafeiyu-bot-style")
                .doesNotContain("{{longTermMemory}}", "{{availableSkills}}");
    }

    private static String toolResult(ChatRequest request, String name) {
        return request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast).filter(m -> m.toolName().equals(name))
                .reduce((first, second) -> second).orElseThrow().text();
    }

    private static AiMessage tool(String id, String name, String arguments) {
        return AiMessage.from(ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build());
    }

    static class RecordingModel implements ChatModel {
        final List<ChatRequest> requests = new ArrayList<>();
        Function<ChatRequest, AiMessage> responder;

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            return ChatResponse.builder().aiMessage(responder.apply(request)).build();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @AutoConfigurationPackage(basePackages = "com.yuntian.service")
    @Import({SkillConfig.class, AgentService.class})
    static class TestConfig {
        @Bean
        static AiServiceScannerProcessor aiServiceScannerProcessor() {
            return new AiServiceScannerProcessor();
        }

        @Bean
        RecordingModel chatModel() {
            return new RecordingModel();
        }

        @Bean
        ChatMemoryProvider chatMemoryProvider() {
            return id -> MessageWindowChatMemory.withMaxMessages(30);
        }

        @Bean
        MemoryService memoryService() {
            return mock(MemoryService.class);
        }
    }
}
