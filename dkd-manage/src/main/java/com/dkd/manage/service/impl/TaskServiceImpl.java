package com.dkd.manage.service.impl;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.dkd.common.constant.DkdContants;
import com.dkd.common.exception.ServiceException;
import com.dkd.common.utils.DateUtils;
import com.dkd.common.utils.StringUtils;
import com.dkd.common.utils.bean.BeanUtils;
import com.dkd.manage.domain.Emp;
import com.dkd.manage.domain.TaskDetails;
import com.dkd.manage.domain.VendingMachine;
import com.dkd.manage.domain.dto.TaskDetailsDto;
import com.dkd.manage.domain.dto.TaskDto;
import com.dkd.manage.mapper.EmpMapper;
import com.dkd.manage.mapper.TaskDetailsMapper;
import com.dkd.manage.mapper.VendingMachineMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import com.dkd.manage.mapper.TaskMapper;
import com.dkd.manage.domain.Task;
import com.dkd.manage.service.ITaskService;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工单Service业务层处理
 *
 * @author gcc
 * @date 2025-11-11
 */
@Service
public class TaskServiceImpl implements ITaskService {
    @Autowired
    private TaskMapper taskMapper;

    @Autowired
    private VendingMachineMapper vendingMachineMapper;

    @Autowired
    private EmpMapper empMapper;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private TaskDetailsMapper taskDetailsMapper;

    /**
     * 查询工单
     *
     * @param taskId 工单主键
     * @return 工单
     */
    @Override
    public Task selectTaskByTaskId(Long taskId) {
        return taskMapper.selectTaskByTaskId(taskId);
    }

    /**
     * 查询工单列表
     *
     * @param task 工单
     * @return 工单
     */
    @Override
    public List<Task> selectTaskList(Task task) {
        return taskMapper.selectTaskList(task);
    }

    /**
     * 新增工单
     *
     * @param task 工单
     * @return 结果
     */
    @Override
    public int insertTask(Task task) {
        task.setCreateTime(DateUtils.getNowDate());
        return taskMapper.insertTask(task);
    }

    /**
     * 修改工单
     *
     * @param task 工单
     * @return 结果
     */
    @Override
    public int updateTask(Task task) {
        task.setUpdateTime(DateUtils.getNowDate());
        return taskMapper.updateTask(task);
    }

    /**
     * 批量删除工单
     *
     * @param taskIds 需要删除的工单主键
     * @return 结果
     */
    @Override
    public int deleteTaskByTaskIds(Long[] taskIds) {
        return taskMapper.deleteTaskByTaskIds(taskIds);
    }

    /**
     * 删除工单信息
     *
     * @param taskId 工单主键
     * @return 结果
     */
    @Override
    public int deleteTaskByTaskId(Long taskId) {
        return taskMapper.deleteTaskByTaskId(taskId);
    }

    /**
     * 查询工单列表
     *
     * @param task
     * @return
     */
    @Override
    public List<Task> selectTaskVoList(Task task) {
        return taskMapper.selectTaskVoList(task);
    }

    /**
     * 新增运营、运维工单
     *
     * @param taskDto
     * @return
     */
    @Transactional
    @Override
    public int insertTaskDto(TaskDto taskDto) {
        //查询售货机是否存在
        VendingMachine vm = vendingMachineMapper.selectVendingMachineByInnerCode(taskDto.getInnerCode());
        if (vm == null) {
            throw new ServiceException("售货机不存在");
        }

        //校验售货机状态与工单类型是否相符
        checkCreateTask(vm.getVmStatus(), taskDto.getProductTypeId());

        //检查设备是否有未完成的同类型订单
        hasTask(taskDto);

        //查询并校验员工是否存在
        Emp emp = empMapper.selectEmpById(taskDto.getUserId());
        if (emp == null) {
            throw new ServiceException("员工不存在");
        }

        //校验员工区域是否匹配
        if (!emp.getRegionId().equals(vm.getRegionId())) {
            throw new ServiceException("员工区域与设备区域不匹配，无法处理此工单");
        }

        //将dto转为po并补充属性，保存工单
        Task task = BeanUtil.copyProperties(taskDto, Task.class);//属性拷贝
        task.setTaskStatus(DkdContants.TASK_STATUS_CREATE);//工单状态设为创建
        task.setUserName(emp.getUserName());//执行人名称
        task.setRegionId(vm.getRegionId());//所属区域id
        task.setAddr(vm.getAddr());//地址
        task.setCreateTime(DateUtils.getNowDate());//创建时间
        task.setTaskCode(generateTaskCode());//工单编号
        int taskResult = taskMapper.insertTask(task);

        //判断是否为补货订单
        if (taskDto.getProductTypeId().equals(DkdContants.TASK_TYPE_SUPPLY)) {
            //保存工单详情
            List<TaskDetailsDto> details = taskDto.getDetails();
            if (CollUtil.isEmpty(details)){
                throw new ServiceException("补货工单详情不能为空");
            }
            //将dto转为po补充属性
            List<TaskDetails> list = details.stream().map(dto -> {
                TaskDetails taskDetails = BeanUtil.copyProperties(dto, TaskDetails.class);
                taskDetails.setTaskId(task.getTaskId());
                return taskDetails;
            }).collect(Collectors.toList());

            //批量新增
           taskDetailsMapper.batchInsertTaskDetails(list);
        }
        return taskResult;
    }

    /**
     * 取消工单
     * @param task
     * @return
     */
    @Override
    public int cancelTask(Task task) {
        //判断工单状态是否可以取消
        //先根据工单id查询数据库
        Task taskDb = taskMapper.selectTaskByTaskId(task.getTaskId());
        //判断工单状态是否为已取消，如果是，则抛出异常
        if (taskDb.getTaskStatus().equals(DkdContants.TASK_STATUS_CANCEL)) {
            throw new ServiceException("工单已取消，不能再次取消");
        }
        //判断工单状态是否为已完成，如果是，则抛出异常
        if (taskDb.getTaskStatus().equals(DkdContants.TASK_STATUS_FINISH)) {
            throw new ServiceException("工单已完成，不能取消");
        }

        //设置更新字段
        task.setTaskStatus(DkdContants.TASK_STATUS_CANCEL);
        task.setUpdateTime(DateUtils.getNowDate());

        //更新工单
        return taskMapper.updateTask(task);
    }

    //生成并获取当天工单编号（唯一标识）
    private String generateTaskCode() {
        //获取当前日期并格式化为“yyyyMMdd”
        String dateStr = DateUtils.getDate().replaceAll("-", "");

        //根据日期生成redis的键
        String key = "dkd.task.code" + dateStr;

        //判断key是否存在
        if (!redisTemplate.hasKey(key)) {
            //如果key不存在，设置初始值为1，并制定过期时间为1天
            redisTemplate.opsForValue().set(key, 1, Duration.ofDays(1));
            //返回工单编号（日期+0001）
            return dateStr + "0001";
        }

        //如果key存在，计数器+1（0002），确保字符串长度为4位
        return dateStr+StrUtil.padPre(redisTemplate.opsForValue().increment(key).toString(), 4, "0");
    }

    //检查设备是否有未完成的同类型订单
    private void hasTask(TaskDto taskDto) {
        //创建task条件对象，并设置设备编号和工单类型，以及工单状态为进行中
        Task task = new Task();
        task.setInnerCode(taskDto.getInnerCode());
        task.setProductTypeId(taskDto.getProductTypeId());
        task.setTaskStatus(DkdContants.TASK_STATUS_PROGRESS);
        //调用taskMapper查询数据库查看是否有符合条件的工单列表
        List<Task> taskList = taskMapper.selectTaskList(task);
        //如果存在未完成的同类型工单，抛出异常
        if (taskList != null && taskList.size() > 0) {
            throw new ServiceException("该设备有未完成的工单，不能创建");
        }
    }

    //校验售货机状态与工单类型是否相符
    private void checkCreateTask(Long vmStatus, Long productTypeId) {
        //如果是投放工单，设备在运行中，抛出异常
        if (productTypeId == DkdContants.TASK_TYPE_DEPLOY && vmStatus == DkdContants.VM_STATUS_RUNNING) {
            throw new ServiceException("该设备状态为运行中，无法进行投放");
        }

        //如果是维修工单，设备不在运行中，抛出异常
        if (productTypeId == DkdContants.TASK_TYPE_REPAIR && vmStatus != DkdContants.VM_STATUS_RUNNING) {
            throw new ServiceException("该设备状态为非运行中，无法进行维修");
        }

        //如果是补货工单，设备不在运行中，抛出异常
        if (productTypeId == DkdContants.TASK_TYPE_SUPPLY && vmStatus != DkdContants.VM_STATUS_RUNNING) {
            throw new ServiceException("该设备状态为非运行中，无法进行补货");
        }

        //如果是撤机工单，设备在运行中，抛出异常
        if (productTypeId == DkdContants.TASK_TYPE_REVOKE && vmStatus == DkdContants.VM_STATUS_RUNNING) {
            throw new ServiceException("该设备状态为运行中，无法进行撤机");
        }
    }
}
