package com.hengzhang;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.*;
import org.springframework.security.core.context.*;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import static com.hengzhang.Domain.*;

@RestController
@RequestMapping("/api")
public class SessionController {
    private final AuthenticationManager manager;
    private final HttpSessionSecurityContextRepository repository;
    private final Store store;
    public SessionController(AuthenticationManager manager, HttpSessionSecurityContextRepository repository, Store store) {
        this.manager=manager; this.repository=repository; this.store=store;
    }
    @GetMapping("/session")
    public Map<String,Object> session(Authentication authentication, CsrfToken csrf) {
        var result=new LinkedHashMap<String,Object>();
        result.put("csrfToken",csrf.getToken());
        result.put("authenticated",authentication != null && authentication.isAuthenticated());
        if(authentication != null) {
            result.put("username",authentication.getName());
            result.put("reviewer",authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_REVIEWER")));
        }
        return result;
    }
    @PostMapping("/login")
    public Map<String,Object> login(@Valid @RequestBody LoginInput input, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication=manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(input.username(),input.password()));
        if(request.getSession(false) != null) request.changeSessionId();
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);repository.saveContext(context,request,response);
        store.audit(authentication.getName(),"LOGIN","session","登录对账中心");
        return Map.of("username",authentication.getName());
    }
    @PostMapping("/logout")
    public Map<String,Object> logout(HttpServletRequest request, Principal principal) {
        store.audit(principal.getName(),"LOGOUT","session","退出对账中心");
        if(request.getSession(false)!=null) request.getSession(false).invalidate();
        SecurityContextHolder.clearContext(); return Map.of("ok",true);
    }
}
