insert into INNER_USER
(id, name, avatar)
values
(1, 'admin', '');

insert into USERNAME_ACCOUNT
(ID ,USERNAME ,PASSWORD ,USER_ID )
values
(1, 'admin', '{noop}123456', 1);

insert into ROLE
(ID, UID ,ROLE )
values
(1, 1, 'ADMIN'),
(2, 1, 'USER');

insert into PERMISSION
(ID, UID  ,RESOURCE ,RESOURCE_ID ,ACTION )
values
(1, 1,'DEMO', '', 'create');

insert into DEMO_ENTITY
(ID, NAME)
values
(1, 'test');

insert into PERMISSION
(ID, UID  ,RESOURCE ,RESOURCE_ID ,ACTION )
values
(2, 1, 'DEMO', '1', 'write');
