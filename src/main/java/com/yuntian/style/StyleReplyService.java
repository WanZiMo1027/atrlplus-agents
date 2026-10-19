package com.yuntian.style;

import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static com.yuntian.style.StyleTypes.*;
import static com.yuntian.style.StyleExampleRenderer.*;

/** 聊天线程只限时等待不可变结果；后台学习、租约和ThreadLocal会话都不进入工作线程。 */
@Slf4j
public class StyleReplyService {
    public record Input(List<Source> history, List<Source> current) {
        public Input { history=List.copyOf(history);current=List.copyOf(current); }
    }
    public record Result(List<Example> examples, String reason, Integer inputTokens, Integer outputTokens, Integer embeddingTokens) {
        public Result { examples=List.copyOf(examples); }
        public static Result empty(String reason) { return new Result(List.of(),reason,null,null,null); }
    }
    private final StyleProperties p;
    private final StyleSceneAnalyzer scenes;
    private final StyleEmbeddingClient embedding;
    private final StyleRepository repository;
    private final StyleExampleRenderer renderer;
    private final ExecutorService workers;
    private final StyleSourceReader reader;
    public StyleReplyService(StyleProperties p, StyleSceneAnalyzer scenes, StyleEmbeddingClient embedding,
            StyleRepository repository, StyleExampleRenderer renderer, ExecutorService workers, StyleSourceReader reader) {
        this.p=p;this.scenes=scenes;this.embedding=embedding;this.repository=repository;this.renderer=renderer;this.workers=workers;this.reader=reader;
    }
    public Input input(String conversation,List<com.yuntian.context.ContextSummaryStore.Batch> history,
                       List<com.yuntian.dto.ChatMessageItem> current,long sequence) {
        var old=history.stream().flatMap(b->b.messages().stream().map(m->reader.normalize(conversation,b.sequence(),m))).filter(Source::human).toList();
        return new Input(old.subList(Math.max(0,old.size()-12),old.size()),current.stream().map(m->reader.normalize(conversation,sequence,m)).toList());
    }
    public Result references(String conversation, Supplier<Input> input) {
        if(!p.getReply().isEnabled()||!p.getConversations().contains(conversation)) return Result.empty("disabled_or_unlisted");
        if(p.getReply().getSampleBudget()==0) return Result.empty("no_sample_budget");
        long start=System.nanoTime();var deadline=StyleReplyDeadline.after(p.getReply().getTimeout());
        Future<Result> task=null;Result result;
        try {
            // 只在已启用且属于白名单时读取原始窗口；Supplier在聊天线程执行，不跨线程传Session。
            Input snapshot=input.get();
            task=workers.submit(()->lookup(conversation,snapshot,deadline));
            result=task.get(deadline.remaining().toNanos(),TimeUnit.NANOSECONDS);
        }catch(RejectedExecutionException busy){result=Result.empty("busy");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();result=Result.empty("interrupted");}
        catch(TimeoutException timeout){result=Result.empty("timeout");}
        catch(ExecutionException failure){
            String code=failure.getCause()==null?null:failure.getCause().getMessage();
            result=Result.empty(code!=null&&code.matches("STYLE_[A-Z0-9_]+")?code:"unavailable");
        }
        catch(Exception failure){result=Result.empty("unavailable");}
        finally { if(task!=null&&!task.isDone()) task.cancel(true); }
        log.info("风格参考检索: conversationId={}, reason={}, candidates={}, fragmentIds={}, elapsedMs={}, inputTokens={}, outputTokens={}, embeddingTokens={}",
                conversation,result.reason(),result.examples().size(),result.examples().stream().map(Example::id).toList(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start),result.inputTokens(),result.outputTokens(),result.embeddingTokens());
        return result;
    }
    private Result lookup(String conversation, Input input, StyleReplyDeadline deadline) {
        if(!repository.hasReadyVectors(conversation,embedding.requestProfile(),deadline)) return Result.empty("no_vectors");
        String body=scenes.input(input.history(),input.current());deadline.remaining();
        var analysis=scenes.analyze(body);deadline.remaining();var scene=analysis.scene();
        if(!scene.usable()) return new Result(List.of(),"unclear_scene",analysis.inputTokens(),analysis.outputTokens(),null);
        var vector=embedding.embed(scene.embeddingText(),deadline.remaining());
        repository.verifyVectorProfile(embedding.requestProfile(),vector,deadline);
        var hits=repository.search(new SearchRequest(conversation,scene.scene(),scene.intent(),scene.seriousness(),p.getReply().getLimit()),vector,deadline);
        var examples=renderer.examples(hits,p.getReply().getSampleBudget());deadline.remaining();
        return new Result(examples,examples.isEmpty()?"no_matching_samples":"selected",analysis.inputTokens(),analysis.outputTokens(),vector.inputTokens());
    }
}
