package retrofit;

import java.util.ArrayList;
import java.util.List;

/**
 * Records methods called against it as a RequestFacade and replays them when called as a
 * RequestInterceptor.
 *
 * 这个拦截器 有点意思
 * 我们外部定义的拦截器,  这个类(继承了RequestFacade 接口 ) 是  intercept 方法参数
 *
 * 但是在内部  执行 http 请求前 这个类 (继承了 RequestInterceptor 接口) 又是一个拦截器
 *
 * 这样就做到了 在拦截的时候, 所有的内柔 都不对外工勘
 * 我们在参数中 获取不到 任务相关的请求信息
 *
 *
 */
final class RequestInterceptorTape implements RequestInterceptor.RequestFacade, RequestInterceptor {

    private final List<CommandWithParams> tape = new ArrayList<CommandWithParams>();

    @Override
    public void addHeader(String name, String value) {
        tape.add(new CommandWithParams(Command.ADD_HEADER, name, value));
    }

    @Override
    public void addPathParam(String name, String value) {
        tape.add(new CommandWithParams(Command.ADD_PATH_PARAM, name, value));
    }

    @Override
    public void addEncodedPathParam(String name, String value) {
        tape.add(new CommandWithParams(Command.ADD_ENCODED_PATH_PARAM, name, value));
    }

    @Override
    public void addQueryParam(String name, String value) {
        tape.add(new CommandWithParams(Command.ADD_QUERY_PARAM, name, value));
    }

    @Override
    public void addEncodedQueryParam(String name, String value) {
        tape.add(new CommandWithParams(Command.ADD_ENCODED_QUERY_PARAM, name, value));
    }

    /**
     * 执行拦截 就是把外部的拦截 在执行一遍
     * @param request
     */
    @Override
    public void intercept(RequestFacade request) {
        for (CommandWithParams cwp : tape) {
            cwp.command.intercept(request, cwp.name, cwp.value);
        }
    }

    private enum Command {
        ADD_HEADER {
            @Override
            public void intercept(RequestFacade facade, String name, String value) {
                facade.addHeader(name, value);
            }
        },
        ADD_PATH_PARAM {
            @Override
            public void intercept(RequestFacade facade, String name, String value) {
                facade.addPathParam(name, value);
            }
        },
        ADD_ENCODED_PATH_PARAM {
            @Override
            public void intercept(RequestFacade facade, String name, String value) {
                facade.addEncodedPathParam(name, value);
            }
        },
        ADD_QUERY_PARAM {
            @Override
            public void intercept(RequestFacade facade, String name, String value) {
                facade.addQueryParam(name, value);
            }
        },
        ADD_ENCODED_QUERY_PARAM {
            @Override
            public void intercept(RequestFacade facade, String name, String value) {
                facade.addEncodedQueryParam(name, value);
            }
        };

        abstract void intercept(RequestFacade facade, String name, String value);
    }

    private static final class CommandWithParams {
        final Command command;
        final String name;
        final String value;

        CommandWithParams(Command command, String name, String value) {
            this.command = command;
            this.name = name;
            this.value = value;
        }
    }
}
