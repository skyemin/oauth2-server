package cn.flizi.auth.service;

import cn.flizi.auth.entity.Sms;
import cn.flizi.auth.entity.User;
import cn.flizi.auth.mapper.SmsMapper;
import cn.flizi.auth.mapper.UserMapper;
import cn.flizi.auth.properties.SocialProperties;
import cn.flizi.auth.security.AuthUser;
import cn.flizi.auth.security.social.SocialDetailsService;
import cn.hutool.http.HttpUtil;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.util.*;

@Service
public class UserService implements UserDetailsService, SocialDetailsService {
    private final UserMapper userMapper;
    private final SmsMapper smsMapper;
    private final SocialProperties socialProperties;

    public UserService(UserMapper userMapper, SmsMapper smsMapper, SocialProperties socialProperties) {
        this.userMapper = userMapper;
        this.smsMapper = smsMapper;
        this.socialProperties = socialProperties;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userMapper.loadUserByUsername(username);
        if (user == null) {
            throw new UsernameNotFoundException("NOT_FOUND_USER");
        }

        return new AuthUser(
                user.getUserId(),
                user.getPassword(),
                true,
                AuthorityUtils.NO_AUTHORITIES
        );
    }

    @Override
    public UserDetails loadUserBySocial(String type, String code, String redirectUri) throws UsernameNotFoundException {
        User user = null;

        // ????????????
        if ("SMS".equals(type)) {
            user = smsHandler(code);
        }
        //github??????
        if("github".equals(type)){
            String githubId = githubHandler(code,redirectUri);
            if(githubId == null){
                return null;
            }
            user = userMapper.loadUserByColumn("github_id", githubId);
            if (user == null) {
                user = new User();
                user.setGithubId(githubId);
                user.setPassword("{bcrypt}" + new BCryptPasswordEncoder()
                        .encode(UUID.randomUUID().toString().replace("_", "")));
                userMapper.insert(user);
            }
        }
        // ??????????????????
        if ("WX_MP".equals(type)) {
            Map<String, String> map = wxMpHandler(code);
            String openId = map.get("openid");
            String unionId = map.get("unionid");
            if (unionId == null) {
                return null;
            }
            user = userMapper.loadUserByColumn("wx_unionid", unionId);
            if (user == null) {
                user = new User();
                user.setWxOpenid(openId);
                user.setWxUnionid(unionId);
                user.setPassword("{bcrypt}" + new BCryptPasswordEncoder()
                        .encode(UUID.randomUUID().toString().replace("_", "")));
                userMapper.insert(user);
            } else if (!StringUtils.hasLength(user.getWxOpenid())) {
                userMapper.updateWxOpenId(user.getUserId(), openId);
            }
        }

        // ??????????????????
        if ("WX_OPEN".equals(type)) {
            String unionId = wxOpenHandler(code);
            user = userMapper.loadUserByColumn("wx_unionid", unionId);
            if (user == null) {
                user = new User();
                user.setWxUnionid(unionId);
                user.setPassword("{bcrypt}" + new BCryptPasswordEncoder()
                        .encode(UUID.randomUUID().toString().replace("_", "")));
                userMapper.insert(user);
            } else if (user.getWxOpenid() != null) {
                userMapper.updateWxOpenId(user.getUserId(), user.getWxOpenid());
            }
        }

        if (user != null) {
            return new AuthUser(user.getUserId(), AuthorityUtils.NO_AUTHORITIES);
        }

        return null;
    }

    private User smsHandler(String codeStr) {
        if (!codeStr.contains(":")) {
            return null;
        }

        String phone = codeStr.split(":")[0];
        String code = codeStr.split(":")[1];
        Sms sms = smsMapper.getCodeByPhone(phone);
        if (sms == null || !sms.getCode().equals(code)
                || new Date().getTime() - sms.getCreateTime().getTime() > 60 * 1000) {
            return null;
        }

        return userMapper.loadUserByColumn("phone", phone);
    }

    public String wxOpenHandler(String code) {
        String uri = String.format("https://api.weixin.qq.com/sns/oauth2/access_token?appid=%s&secret=%s&code=%s&grant_type=authorization_code",
                socialProperties.getWxOpen().getKey(),
                socialProperties.getWxOpen().getSecret(),
                code);
        Map<String, Object> map = getStringObjectMap(uri,HttpMethod.POST);
        return (String) map.get("unionid");
    }

    public Map<String, String> wxMpHandler(String code) {
        String uri = String.format("https://api.weixin.qq.com/sns/oauth2/access_token?appid=%s&secret=%s&code=%s&grant_type=authorization_code",
                socialProperties.getWxMp().getKey(),
                socialProperties.getWxMp().getSecret(),
                code);
        Map<String, Object> map = getStringObjectMap(uri,HttpMethod.POST);
        String openId = (String) map.get("openid");
        String unionId = (String) map.get("unionid");

        Map<String, String> result = new HashMap<>();
        result.put("openid", openId);
        result.put("unionid", unionId);
        return result;
    }

    public String githubHandler(String code,String redirectUri) {
       /* String proxy = "a.xyms.online";  // 100.100.101.235 8811  ?????????????????????????????????ip
        int port = 8800;   //??????????????????*/
/*        System.setProperty("proxyType", "4");
        System.setProperty("proxyPort", Integer.toString(port));
        System.setProperty("proxyHost", proxy);
        System.setProperty("proxySet", "true");*/
        String uri = String.format("https://gitee.com/oauth/token?grant_type=authorization_code&code=%s&client_id=%s&redirect_uri=%s&client_secret=%s",
                code,
                socialProperties.getGithub().getKey(),
                "http://localhost:8080/auth-redirect",
                socialProperties.getGithub().getSecret());
  /*      String s = HttpUtil.post(uri,"");
        String s = HttpUtil.get(uri);
        System.out.println(s);*/
        Map<String, Object> map = getStringObjectMap(uri,HttpMethod.POST);
        String accessToken = (String) map.get("access_token");
        //??????????????????
        String githubUrl = "https://gitee.com/api/v5/user";
        Map<String, Object> map1 = getGithubMap(githubUrl,HttpMethod.GET,accessToken);
        return map1.get("id").toString();
    }
    public Map<String, Object> getGithubMap(String url,HttpMethod httpMethod,String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + accessToken);
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getMessageConverters().add(new WxMappingJackson2HttpMessageConverter());
        return restTemplate.exchange(url, httpMethod, new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .getBody();
    }


    public static Map<String, Object> getStringObjectMap(String url, HttpMethod httpMethod) {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getMessageConverters().add(new WxMappingJackson2HttpMessageConverter());
        return restTemplate.exchange(url, httpMethod, new HttpEntity<>(new HttpHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .getBody();
    }

    private static class WxMappingJackson2HttpMessageConverter extends MappingJackson2HttpMessageConverter {
        public WxMappingJackson2HttpMessageConverter() {
            List<MediaType> mediaTypes = new ArrayList<>();
            mediaTypes.add(MediaType.TEXT_PLAIN);
            mediaTypes.add(MediaType.TEXT_HTML);  // ??????????????????:  ??????????????? text/plain ?????????
            setSupportedMediaTypes(mediaTypes);
        }
    }

    public static void main(String[] args) throws IOException {
        String url = "https://github.com/",
                proxy = "a.xyms.online",
                port = "8800";
        URL server = new URL(url);
        Properties systemProperties = System.getProperties();
        systemProperties.setProperty("http.proxyHost",proxy);
        systemProperties.setProperty("http.proxyPort",port);
        HttpURLConnection connection = (HttpURLConnection)server.openConnection();
        connection.connect();
        InputStream in = connection.getInputStream();
        System.out.println(in);
    }
}
