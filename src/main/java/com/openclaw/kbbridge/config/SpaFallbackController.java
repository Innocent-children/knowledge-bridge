package com.openclaw.kbbridge.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * SPA 回退控制器。
 * <p>
 * 将不匹配 API 路由和静态文件的 GET 请求转发到 index.html，
 * 以支持前端 React Router 的客户端路由。
 * </p>
 */
@Controller
public class SpaFallbackController {

    /**
     * 匹配不含文件扩展名的路径（即非静态资源），转发到 index.html。
     * <p>
     * 正则 {@code [^\\.]*} 排除了包含点号的路径（如 .js、.css、.png），
     * 确保静态文件请求不会被拦截。
     * API 路由（/api/**）由 @RestController 优先匹配，不会到达此处。
     * </p>
     *
     * @return 转发到 /index.html
     */
    @RequestMapping(value = {"/{path:[^\\.]*}", "/{path:[^\\.]*}/{subpath:[^\\.]*}",
            "/{path:[^\\.]*}/{subpath:[^\\.]*}/{remaining:[^\\.]*}"}, method = RequestMethod.GET)
    public String forward() {
        return "forward:/index.html";
    }
}
